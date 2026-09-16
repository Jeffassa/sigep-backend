package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.dto.response.LignePaie;
import ci.esatic.sigep.entity.Classe;
import ci.esatic.sigep.entity.Emargement;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Matiere;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.Salle;
import ci.esatic.sigep.entity.Seance;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.StatutSeance;
import ci.esatic.sigep.repository.ClasseRepository;
import ci.esatic.sigep.repository.EmargementRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.MatiereRepository;
import ci.esatic.sigep.repository.SalleRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import ci.esatic.sigep.service.ExportPaieService;
import ci.esatic.sigep.tenant.TenantContext;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Export des heures pour le service paie.
 *
 * <p>C'est le seul endroit de SIGEP dont la sortie sert à payer quelqu'un. Une heure perdue en
 * route n'est pas un défaut d'affichage : c'est un salaire amputé, constaté par l'enseignant un
 * mois plus tard, et impossible à reconstituer si personne n'a gardé trace de la règle
 * appliquée. Ces tests figent cette règle.
 *
 * <p>Ils tiennent aussi la frontière que le produit s'est fixée : le fichier livre des
 * <b>heures constatées</b>, jamais un montant. Un montant supposerait un taux horaire que SIGEP
 * ne connaît pas — et un chiffre d'argent inventé sur une fiche de paie coûte cher à quelqu'un
 * de réel.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExportPaieTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private EntityManager em;
    @Autowired private ExportPaieService exportPaieService;
    @Autowired private PlanService planService;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private SeanceRepository seanceRepository;
    @Autowired private EmargementRepository emargementRepository;
    @Autowired private MatiereRepository matiereRepository;
    @Autowired private ClasseRepository classeRepository;
    @Autowired private SalleRepository salleRepository;

    private static final YearMonth MOIS = YearMonth.of(2026, 3);

    /** Le mois est clos : tout ce qu'il contient a eu lieu. */
    private static final LocalDateTime APRES = LocalDateTime.of(2026, 4, 1, 8, 0);

    private Long tenant;
    private Matiere matiere;
    private Classe classe;
    private Salle salle;

    @AfterEach
    void nettoyer() {
        TenantContext.clear();
        em.unwrap(Session.class).disableFilter("tenantFilter");
    }

    // ───────────────────────── le calcul des heures ─────────────────────────

    @Test
    void lesHeuresAPayer_sontLaDureeDesSeancesEmargees() {
        preparer();
        Enseignant e = enseignant("PAIE-1", "Koffi", StatutEnseignant.VALIDATED);
        seance(e, 3, "08:00", "09:30", StatutSeance.EMARGE);   // 1 h 30
        seance(e, 4, "14:00", "16:00", StatutSeance.EMARGE);   // 2 h 00
        rendreVisible();

        LignePaie l = ligne("PAIE-1");
        assertThat(l.heures()).isEqualTo(3.5);
        assertThat(l.seancesEmargees()).isEqualTo(2);
        assertThat(l.seancesPrevues()).isEqualTo(2);
        assertThat(l.estNette()).isTrue();
    }

    @Test
    void uneSeanceSansEmargement_neSePaiePas_maisNeDisparaitPas() {
        // La règle qui compte. Un oubli de scan existe : retrancher l'heure en silence ferait
        // porter un incident technique par l'enseignant, et personne ne le verrait passer.
        // Elle est donc sortie du total ET affichée, avec les heures en jeu.
        preparer();
        Enseignant e = enseignant("PAIE-2", "Amani", StatutEnseignant.VALIDATED);
        seance(e, 5, "08:00", "10:00", StatutSeance.EMARGE);     // payée
        seance(e, 6, "08:00", "11:00", StatutSeance.A_FAIRE);    // à trancher
        rendreVisible();

        LignePaie l = ligne("PAIE-2");
        assertThat(l.heures()).isEqualTo(2.0);
        assertThat(l.seancesNonEmargees()).isEqualTo(1);
        assertThat(l.heuresNonEmargees()).isEqualTo(3.0);
        assertThat(l.estNette()).isFalse();
    }

    @Test
    void uneSeanceHorsLigneNonValidee_estSuspendue_pasPerdue() {
        // L'enseignant a émargé sans réseau ; l'administration n'a pas encore confirmé. Les
        // heures ne sont pas payables en l'état, mais elles lui sont dues si la confirmation
        // vient. Les compter d'office paierait une présence non vérifiée ; les jeter ferait
        // dépendre un salaire de la promptitude d'un administrateur.
        preparer();
        Enseignant e = enseignant("PAIE-3", "Traore", StatutEnseignant.VALIDATED);
        seance(e, 9, "08:00", "12:00", StatutSeance.EN_ATTENTE_VALIDATION);
        rendreVisible();

        LignePaie l = ligne("PAIE-3");
        assertThat(l.heures()).isZero();
        assertThat(l.seancesEnAttente()).isEqualTo(1);
        assertThat(l.heuresEnAttente()).isEqualTo(4.0);
        assertThat(l.estNette()).isFalse();
    }

    @Test
    void lesRetardsEtLesHorsLigne_sontComptes_sansOterUneHeure() {
        // Un retard est un fait, pas une sanction. La séance est comptée entière : décider
        // qu'un quart d'heure de retard coûte une heure de salaire n'est pas une décision
        // d'algorithme.
        preparer();
        Enseignant e = enseignant("PAIE-4", "Bamba", StatutEnseignant.VALIDATED);
        Seance s = seance(e, 10, "08:00", "10:00", StatutSeance.EMARGE);
        emargement(s, e, true, false);
        rendreVisible();

        LignePaie l = ligne("PAIE-4");
        assertThat(l.heures()).isEqualTo(2.0);
        assertThat(l.retards()).isEqualTo(1);
        assertThat(l.horsLigne()).isZero();
    }

    @Test
    void unEnseignantEnActiviteSansSeance_figureAZero() {
        // Une ligne à zéro se lit. Une ligne absente laisse le doute entre « n'a pas
        // travaillé » et « a été oublié », et c'est au service paie de trancher, pas au
        // fichier de choisir pour lui.
        preparer();
        enseignant("PAIE-5", "Diallo", StatutEnseignant.VALIDATED);
        rendreVisible();

        LignePaie l = ligne("PAIE-5");
        assertThat(l.heures()).isZero();
        assertThat(l.seancesPrevues()).isZero();
    }

    @Test
    void unEnseignantParti_quiAtravaille_estQuandMemePaye() {
        // Les heures faites se paient, même par quelqu'un qui a quitté l'établissement depuis.
        // Le filtrer sur son statut actuel le rayerait du fichier du mois où il a enseigné.
        preparer();
        Enseignant e = enseignant("PAIE-6", "Yao", StatutEnseignant.ARCHIVE);
        seance(e, 12, "08:00", "10:00", StatutSeance.EMARGE);
        rendreVisible();

        assertThat(ligne("PAIE-6").heures()).isEqualTo(2.0);
    }

    @Test
    void unEnseignantParti_sansAucuneSeance_neFigurePas() {
        // Le pendant du test précédent : sans heures sur le mois, un enseignant archivé
        // n'a rien à faire dans le fichier — il l'allongerait sans rien y apporter.
        preparer();
        enseignant("PAIE-7", "Kone", StatutEnseignant.ARCHIVE);
        rendreVisible();

        assertThat(exportPaieService.calculer(MOIS, APRES))
                .extracting(LignePaie::matricule).doesNotContain("PAIE-7");
    }

    @Test
    void lesLignesAtrancher_remontentEnTete() {
        // L'écran sert à repérer ce qui reste à faire avant l'envoi. Une ligne nette n'appelle
        // aucune action ; une ligne litigieuse, si.
        preparer();
        Enseignant net = enseignant("PAIE-A", "Aaa", StatutEnseignant.VALIDATED);
        seance(net, 3, "08:00", "10:00", StatutSeance.EMARGE);
        Enseignant litige = enseignant("PAIE-Z", "Zzz", StatutEnseignant.VALIDATED);
        seance(litige, 3, "08:00", "10:00", StatutSeance.A_FAIRE);
        rendreVisible();

        List<LignePaie> lignes = exportPaieService.calculer(MOIS, APRES);
        assertThat(lignes.get(0).matricule())
                .as("l'enseignant dont la ligne demande un arbitrage doit passer devant")
                .isEqualTo("PAIE-Z");
    }

    @Test
    void lesSeancesDUnAutreMois_nEntrentPasDansLeTotal() {
        preparer();
        Enseignant e = enseignant("PAIE-8", "Ouattara", StatutEnseignant.VALIDATED);
        seance(e, 15, "08:00", "10:00", StatutSeance.EMARGE);
        // Dernier jour du mois précédent, et premier du suivant : les deux bornes à la fois.
        seanceLe(e, MOIS.atDay(1).minusDays(1), "08:00", "10:00", StatutSeance.EMARGE);
        seanceLe(e, MOIS.atEndOfMonth().plusDays(1), "08:00", "10:00", StatutSeance.EMARGE);
        rendreVisible();

        assertThat(ligne("PAIE-8").heures()).isEqualTo(2.0);
    }

    @Test
    void lesBornesDuMois_sontIncluses() {
        preparer();
        Enseignant e = enseignant("PAIE-9", "Sangare", StatutEnseignant.VALIDATED);
        seanceLe(e, MOIS.atDay(1), "08:00", "09:00", StatutSeance.EMARGE);
        seanceLe(e, MOIS.atEndOfMonth(), "08:00", "09:00", StatutSeance.EMARGE);
        rendreVisible();

        assertThat(ligne("PAIE-9").heures()).isEqualTo(2.0);
    }

    // ───────────── les deux facons de perdre une heure en silence ─────────────

    @Test
    void unCoursDuSoirQuiFranchitMinuit_estPayeEntierement() {
        // 21 h -> 00 h : la soustraction naive donne moins vingt et une heures. Ramenee a zero,
        // elle payait le vacataire zero heure pour trois heures faites, sans que la ligne soit
        // meme signalee — la ligne paraissait nette. C'est exactement l'amputation silencieuse
        // que ce fichier existe pour empecher.
        preparer();
        Enseignant e = enseignant("PAIE-NUIT", "Cisse", StatutEnseignant.VALIDATED);
        seance(e, 17, "21:00", "00:00", StatutSeance.EMARGE);
        rendreVisible();

        LignePaie l = ligne("PAIE-NUIT");
        assertThat(l.heures()).isEqualTo(3.0);
        assertThat(l.seancesEmargees()).isEqualTo(1);
        assertThat(l.seancesDureeIncoherente()).isZero();
        assertThat(l.estNette()).isTrue();
    }

    @Test
    void desHorairesInverses_sontSignales_jamaisComptesAZeroEnSilence() {
        // 10 h -> 08 h ne franchit pas minuit : ce sont deux champs echanges. Le lire comme
        // vingt-deux heures paierait une journee entiere ; le lire comme zero effacerait le
        // cours. On refuse de deviner, et on le dit : la ligne cesse d'etre nette.
        preparer();
        Enseignant e = enseignant("PAIE-INV", "Konan", StatutEnseignant.VALIDATED);
        seance(e, 18, "10:00", "08:00", StatutSeance.EMARGE);
        rendreVisible();

        LignePaie l = ligne("PAIE-INV");
        assertThat(l.heures()).isZero();
        assertThat(l.seancesEmargees()).as("rien ne doit etre paye sur une duree indechiffrable").isZero();
        assertThat(l.seancesDureeIncoherente()).isEqualTo(1);
        assertThat(l.estNette()).as("la ligne doit remonter pour correction").isFalse();
    }

    @Test
    void uneSeanceDeDureeNulle_estSignaleeElleAussi() {
        preparer();
        Enseignant e = enseignant("PAIE-NUL", "Gnahore", StatutEnseignant.VALIDATED);
        seance(e, 19, "08:00", "08:00", StatutSeance.EMARGE);
        rendreVisible();

        assertThat(ligne("PAIE-NUL").seancesDureeIncoherente()).isEqualTo(1);
    }

    @Test
    void uneSeanceQuiNAPasEncoreEuLieu_nEstPasUnOubliDEmargement() {
        // Le 15 a midi, les cours du 20 sont encore au planning. Les compter comme des
        // manquements ferait remonter tout l'etablissement en tete du tableau, et le vrai oubli
        // de scan disparaitrait dans le bruit.
        preparer();
        Enseignant e = enseignant("PAIE-FUT", "Coulibaly", StatutEnseignant.VALIDATED);
        seance(e, 20, "08:00", "10:00", StatutSeance.A_FAIRE);
        rendreVisible();

        LocalDateTime le15aMidi = LocalDateTime.of(2026, 3, 15, 12, 0);
        LignePaie l = exportPaieService.calculer(MOIS, le15aMidi).stream()
                .filter(x -> "PAIE-FUT".equals(x.matricule())).findFirst().orElseThrow();

        assertThat(l.seancesAVenir()).isEqualTo(1);
        assertThat(l.heuresAVenir()).isEqualTo(2.0);
        assertThat(l.seancesNonEmargees()).isZero();
        assertThat(l.estNette()).as("un cours a venir n'appelle aucun arbitrage").isTrue();
    }

    @Test
    void laMemeSeance_unefoisPassee_redevientUnOubliDEmargement() {
        // Le pendant du test precedent : la seance n'est pas exoneree, elle attend son heure.
        preparer();
        Enseignant e = enseignant("PAIE-FUT2", "Coulibaly", StatutEnseignant.VALIDATED);
        seance(e, 20, "08:00", "10:00", StatutSeance.A_FAIRE);
        rendreVisible();

        LignePaie l = ligne("PAIE-FUT2");   // lu apres la fin du mois
        assertThat(l.seancesAVenir()).isZero();
        assertThat(l.seancesNonEmargees()).isEqualTo(1);
        assertThat(l.estNette()).isFalse();
    }

    @Test
    void uneSeanceEnCours_nEstPasEncoreUnOubli() {
        // 08:00-10:00, il est 09:00 : l'enseignant a jusqu'a la fin du creneau pour scanner.
        preparer();
        Enseignant e = enseignant("PAIE-ENCOURS", "Adou", StatutEnseignant.VALIDATED);
        seance(e, 20, "08:00", "10:00", StatutSeance.A_FAIRE);
        rendreVisible();

        LignePaie l = exportPaieService.calculer(MOIS, LocalDateTime.of(2026, 3, 20, 9, 0))
                .stream().filter(x -> "PAIE-ENCOURS".equals(x.matricule())).findFirst().orElseThrow();
        assertThat(l.seancesAVenir()).isEqualTo(1);
        assertThat(l.seancesNonEmargees()).isZero();
    }

    @Test
    void lesQuatreSorts_couvrentToutesLesSeances() {
        // Invariant d'equilibre : aucune seance ne doit s'evaporer entre les colonnes, sans
        // quoi le fichier perdrait des heures sans qu'aucun total ne le montre.
        preparer();
        Enseignant e = enseignant("PAIE-SOMME", "Bakayoko", StatutEnseignant.VALIDATED);
        seance(e, 3, "08:00", "10:00", StatutSeance.EMARGE);
        seance(e, 4, "08:00", "10:00", StatutSeance.EN_ATTENTE_VALIDATION);
        seance(e, 5, "08:00", "10:00", StatutSeance.A_FAIRE);
        seance(e, 6, "10:00", "08:00", StatutSeance.EMARGE);       // horaires inverses
        rendreVisible();

        LignePaie l = ligne("PAIE-SOMME");
        assertThat(l.seancesEmargees() + l.seancesEnAttente() + l.seancesNonEmargees()
                + l.seancesAVenir() + l.seancesDureeIncoherente())
                .as("chaque seance prevue doit se retrouver dans exactement une colonne")
                .isEqualTo(l.seancesPrevues());
    }

    // ──────────────────────────── les fichiers ─────────────────────────────

    @Test
    void leCsv_neLaissePasUneFormuleSExecuter() {
        // Nos noms et matricules viennent d'imports Excel fournis par l'établissement : ils ne
        // sont pas de notre main. Une cellule commençant par « = » est exécutée à l'ouverture
        // (CWE-1236) — et le fichier de paie est précisément celui qu'on ouvre sans méfiance.
        String csv = new String(exportPaieService.versCsv(
                List.of(ligneFictive("=1+1", "@SUM(A1)", "+42")), MOIS), StandardCharsets.UTF_8);

        assertThat(csv).doesNotContain("\"=1+1\"").contains("\"'=1+1\"");
        assertThat(csv).contains("\"'@SUM(A1)\"");
        assertThat(csv).contains("\"'+42\"");
    }

    @Test
    void leCsv_sOuvreEnColonnesDansUnExcelFrancophone() {
        String csv = new String(exportPaieService.versCsv(
                List.of(ligneFictive("M-1", "N'Guessan", "Améyo")), MOIS), StandardCharsets.UTF_8);

        assertThat(csv.charAt(0)).as("le BOM, sans quoi les accents sont illisibles").isEqualTo('﻿');
        assertThat(csv).contains("Améyo");                 // l'UTF-8 traverse intact
        assertThat(csv).contains("\"Matricule\";\"Nom\"");   // point-virgule : séparateur fr
        assertThat(csv).contains("\r\n");                   // RFC 4180
        assertThat(csv).contains("3,50");  // décimale en virgule
    }

    @Test
    void leCsv_survitAUnNomPorteurDeGuillemetsOuDeSeparateurs() {
        String csv = new String(exportPaieService.versCsv(
                List.of(ligneFictive("M-2", "O\"Brien; fils", "Jean\nPaul")), MOIS),
                StandardCharsets.UTF_8);

        // Guillemet doublé, champ entre guillemets : le point-virgule et le saut de ligne
        // restent dans la cellule au lieu de décaler toute la ligne.
        assertThat(csv).contains("\"O\"\"Brien; fils\"");
        assertThat(csv).contains("\"Jean\nPaul\"");
    }

    @Test
    void leFichierExcel_porteDesNombres_pasDuTexte() throws Exception {
        byte[] xlsx = exportPaieService.versExcel(List.of(ligneFictive("M-3", "Kouassi", "Adjo")), MOIS);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var feuille = wb.getSheetAt(0);
            assertThat(feuille.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Matricule");
            // Colonne 8 = heures. En texte, la somme de la colonne serait impossible.
            var heures = feuille.getRow(1).getCell(8);
            assertThat(heures.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(heures.getNumericCellValue()).isEqualTo(3.5);
        }
    }

    @Test
    void lesDeuxFormats_portentLesMemesColonnes() throws Exception {
        // Deux écritures séparées finissent toujours par diverger : la première colonne ajoutée
        // d'un côté seulement rend les deux fichiers incomparables pour qui reçoit les deux.
        List<LignePaie> lignes = List.of(ligneFictive("M-4", "Sow", "Fatou"));
        String enteteCsv = new String(exportPaieService.versCsv(lignes, MOIS), StandardCharsets.UTF_8)
                .substring(1).split("\r\n")[0];

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(
                exportPaieService.versExcel(lignes, MOIS)))) {
            var entete = wb.getSheetAt(0).getRow(0);
            for (int i = 0; i < ExportPaieService.colonnes().size(); i++) {
                assertThat(entete.getCell(i).getStringCellValue())
                        .isEqualTo(ExportPaieService.colonnes().get(i));
                assertThat(enteteCsv).contains("\"" + ExportPaieService.colonnes().get(i) + "\"");
            }
        }
    }

    @Test
    void aucunMontant_nEstInvente() {
        // SIGEP ne connaît aucun taux horaire : ni le grade, ni l'ancienneté, ni la convention
        // qui le fixent n'existent dans ses données. Une colonne « montant » serait un chiffre
        // d'argent fabriqué, sur un document qui sert à payer de vraies personnes.
        String colonnes = String.join(" ", ExportPaieService.colonnes()).toLowerCase();
        assertThat(colonnes)
                .doesNotContain("montant").doesNotContain("taux")
                .doesNotContain("salaire").doesNotContain("fcfa").doesNotContain("euro");
    }

    // ────────────────────────────── cloisonnement ─────────────────────────────

    @Test
    void horsContexteDEtablissement_leCalculRefuseDeRepondre() {
        // Une sonde a montré que sans contexte ni filtre, le calcul rendait tranquillement les
        // lignes de DEUX établissements. Aucun chemin web n'y mène — TenantInterceptor pose
        // toujours les deux — mais brancher demain cet export sur une tâche planifiée
        // (« envoyer l'état de paie le 28 ») suffirait : une tâche s'exécute sans requête, donc
        // sans filtre. Le fichier mélangerait les salaires de tous les établissements.
        preparer();
        enseignant("PAIE-CLOISON", "Silue", StatutEnseignant.VALIDATED);
        em.flush();
        em.clear();
        TenantContext.clear();
        em.unwrap(Session.class).disableFilter("tenantFilter");

        assertThatThrownBy(() -> exportPaieService.calculer(MOIS, APRES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("etablissement");
    }

    // ──────────────────────────────── le plan ──────────────────────────────

    @Test
    void lExport_estCompteDansLesPlansQuiLeVendent() {
        assertThat(planService.estDisponible(plan(Plan.FREE), Feature.EXPORT_PAIE)).isFalse();
        assertThat(planService.estDisponible(plan(Plan.PRO), Feature.EXPORT_PAIE)).isTrue();
        assertThat(planService.estDisponible(plan(Plan.ENTERPRISE), Feature.EXPORT_PAIE)).isTrue();
    }

    // ─────────────────────────────── outillage ─────────────────────────────

    private Etablissement plan(Plan p) {
        Etablissement e = new Etablissement();
        e.setPlan(p);
        return e;
    }

    /** Crée l'établissement du test et son référentiel minimal, filtre désactivé. */
    private void preparer() {
        em.unwrap(Session.class).disableFilter("tenantFilter");
        tenant = etablissementRepository.save(Etablissement.builder()
                .nom("Ecole paie").slug("ecole-paie-" + System.nanoTime()).plan(Plan.PRO).build()).getId();

        matiere = Matiere.builder().libelle("Algorithmique").build();
        matiere.setEtablissementId(tenant);
        matiere = matiereRepository.save(matiere);

        classe = Classe.builder().libelle("L1").build();
        classe.setEtablissementId(tenant);
        classe = classeRepository.save(classe);

        salle = Salle.builder().libelle("A101").build();
        salle.setEtablissementId(tenant);
        salle = salleRepository.save(salle);
    }

    /**
     * Bascule dans la vue de l'établissement du test : au-delà, on ne voit que lui.
     *
     * <p>Contexte ET filtre, comme le fait {@code TenantInterceptor} à chaque requête. Poser
     * seulement le filtre ferait passer les tests dans un état qui n'existe pas en production,
     * et laisserait le refus hors contexte sans preuve.
     */
    private void rendreVisible() {
        em.flush();
        em.clear();
        TenantContext.set(tenant);
        Session s = em.unwrap(Session.class);
        s.disableFilter("tenantFilter");
        s.enableFilter("tenantFilter").setParameter("tenantId", tenant);
    }

    private Enseignant enseignant(String matricule, String nom, StatutEnseignant statut) {
        Enseignant e = Enseignant.builder()
                .matricule(matricule).nom(nom).prenom("Test").grade("Vacataire")
                .departement("Informatique").statut(statut).build();
        e.setEtablissementId(tenant);
        return enseignantRepository.save(e);
    }

    private Seance seance(Enseignant e, int jour, String debut, String fin, StatutSeance statut) {
        return seanceLe(e, MOIS.atDay(jour), debut, fin, statut);
    }

    private Seance seanceLe(Enseignant e, LocalDate date, String debut, String fin, StatutSeance statut) {
        Seance s = Seance.builder()
                .date(date).heureDebut(LocalTime.parse(debut)).heureFin(LocalTime.parse(fin))
                .matiere(matiere).classe(classe).salle(salle).enseignant(e).statut(statut).build();
        s.setEtablissementId(tenant);
        return seanceRepository.save(s);
    }

    private void emargement(Seance s, Enseignant e, boolean enRetard, boolean horsLigne) {
        Emargement em2 = Emargement.builder()
                .seance(s).enseignant(e)
                .dateHeure(LocalDateTime.of(s.getDate(), s.getHeureDebut()))
                .enRetard(enRetard).horsLigne(horsLigne).build();
        em2.setEtablissementId(tenant);
        emargementRepository.save(em2);
    }

    private LignePaie ligne(String matricule) {
        return exportPaieService.calculer(MOIS, APRES).stream()
                .filter(l -> matricule.equals(l.matricule())).findFirst()
                .orElseThrow(() -> new AssertionError("ligne absente pour " + matricule));
    }

    /** Une ligne fabriquée à la main : les tests de format n'ont pas besoin de la base. */
    private LignePaie ligneFictive(String matricule, String nom, String prenom) {
        return new LignePaie(matricule, nom, prenom, "Vacataire", "Informatique",
                3, 2, 3.5, 1, 0, 0, 0.0, 1, 1.5, 0, 0.0, 0);
    }
}
