package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.response.LignePaie;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Seance;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.StatutSeance;
import ci.esatic.sigep.repository.EmargementRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import ci.esatic.sigep.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Export des heures faites, par enseignant et par mois, pour le service paie.
 *
 * <p>Ce n'est pas un connecteur vers un logiciel de paie et cela ne prétend pas l'être. C'est un
 * fichier que Sage, Odoo, un tableur ou un logiciel maison savent tous lire — ce qui, en
 * pratique, sert mieux qu'un connecteur écrit pour un logiciel qu'on n'a pas.
 *
 * <p>Deux formats, pour deux lecteurs :
 * <ul>
 *   <li><b>CSV</b>, à la convention française (point-virgule, décimale en virgule, BOM UTF-8) :
 *       il s'ouvre en colonnes dans un Excel francophone, et les accents s'affichent. Sans le
 *       BOM, « Koffi Amani N'Guessan » devient « KoffiÂ… » ; sans le point-virgule, tout le
 *       fichier atterrit dans la colonne A.</li>
 *   <li><b>XLSX</b>, où les nombres sont de vrais nombres : aucune ambiguïté de séparateur
 *       décimal, la somme d'une colonne est immédiate.</li>
 * </ul>
 *
 * <p>Une règle traverse tout ce fichier : <b>aucune heure ne disparaît en silence</b>. Une
 * séance qu'on ne peut pas payer se retrouve toujours dans une colonne qui la nomme — en
 * attente, sans émargement, à venir, ou horaires incohérents. C'est le seul moyen pour que
 * l'écart se voie du côté de l'administration plutôt que sur le bulletin de l'enseignant.
 */
@Service
@RequiredArgsConstructor
public class ExportPaieService {

    private final SeanceRepository seanceRepository;
    private final EmargementRepository emargementRepository;
    private final EnseignantRepository enseignantRepository;

    /** En-têtes des deux formats, dans l'ordre. Une seule source pour ne pas les voir diverger. */
    private static final String[] COLONNES = {
            "Matricule", "Nom", "Prenom", "Grade", "Departement", "Periode",
            "Seances prevues", "Seances emargees", "Heures a payer",
            "Retards", "Hors-ligne",
            "Seances en attente", "Heures en attente",
            "Seances non emargees", "Heures non emargees",
            "Seances a venir", "Heures a venir",
            "Horaires incoherents"
    };

    /** Indice de la colonne « Heures a payer », seule à recevoir un total en bas de feuille. */
    private static final int COLONNE_HEURES = 8;

    /** Les en-têtes, en lecture seule : le tableau ci-dessus reste privé parce qu'il est modifiable. */
    public static List<String> colonnes() {
        return List.of(COLONNES);
    }

    /** Le mois demandé, arrêté à l'instant présent. */
    @Transactional(readOnly = true)
    public List<LignePaie> calculer(YearMonth mois) {
        return calculer(mois, LocalDateTime.now());
    }

    /**
     * Calcule une ligne par enseignant concerné par le mois demandé.
     *
     * <p>Trois requêtes, quel que soit le nombre d'enseignants : le corps enseignant, les séances
     * de la période, un agrégat des émargements. Le calcul se fait ensuite en mémoire. La boucle
     * « un enseignant, deux requêtes » qu'on trouve ailleurs dans le code tient tant qu'on a
     * trente enseignants ; un export de paie est justement l'endroit où l'établissement est le
     * plus gros. C'est aussi pourquoi les enseignants sont chargés d'abord : sans eux en
     * mémoire, chaque séance réveillerait son proxy et la boucle redeviendrait un N+1.
     *
     * <p>Qui figure dans le fichier : tout enseignant ayant au moins une séance sur le mois —
     * y compris s'il a quitté l'établissement depuis, parce que les heures faites se paient —
     * plus tout enseignant en activité, même à zéro heure. Une ligne à zéro se lit ; une ligne
     * absente laisse le doute entre « n'a pas travaillé » et « a été oublié ».
     *
     * @param maintenant l'instant qui sépare ce qui a eu lieu de ce qui reste à venir. Passé en
     *     paramètre pour que le calcul soit reproductible : une règle qui décide du salaire ne
     *     doit pas dépendre de l'horloge au moment où on la teste.
     */
    @Transactional(readOnly = true)
    public List<LignePaie> calculer(YearMonth mois, LocalDateTime maintenant) {
        exigerUnEtablissement();
        var debut = mois.atDay(1);
        var fin = mois.atEndOfMonth();

        // Le corps enseignant d'abord : les séances ne portent que des proxys, et les résoudre
        // un par un ferait autant de requêtes que d'enseignants.
        Map<Long, Enseignant> tous = new LinkedHashMap<>();
        for (Enseignant e : enseignantRepository.findAll()) tous.put(e.getId(), e);

        Map<Long, Cumul> parEnseignant = new HashMap<>();
        Map<Long, Enseignant> connus = new LinkedHashMap<>();
        for (Seance s : seanceRepository.findAllByDateBetweenOrdered(debut, fin)) {
            Enseignant reference = s.getEnseignant();
            if (reference == null) continue;   // séance orpheline : rien à payer à personne
            // getId() sur un proxy rend l'identifiant sans déclencher le chargement.
            Long id = reference.getId();
            connus.putIfAbsent(id, tous.getOrDefault(id, reference));
            parEnseignant.computeIfAbsent(id, k -> new Cumul()).ajouter(s, maintenant);
        }

        // Retards et hors-ligne : un agrégat, pas une requête par enseignant.
        Map<Long, long[]> signaux = new HashMap<>();
        for (Object[] ligne : emargementRepository.signauxParEnseignant(debut, fin)) {
            signaux.put(((Number) ligne[0]).longValue(),
                    new long[] {((Number) ligne[1]).longValue(), ((Number) ligne[2]).longValue()});
        }

        // Les enseignants en activité sans aucune séance : présents, à zéro.
        for (Enseignant e : tous.values()) {
            if (e.getStatut() == StatutEnseignant.VALIDATED) connus.putIfAbsent(e.getId(), e);
        }

        List<LignePaie> lignes = new ArrayList<>(connus.size());
        for (Enseignant e : connus.values()) {
            Cumul c = parEnseignant.getOrDefault(e.getId(), new Cumul());
            long[] s = signaux.getOrDefault(e.getId(), new long[] {0, 0});
            lignes.add(new LignePaie(
                    e.getMatricule(), e.getNom(), e.getPrenom(),
                    vide(e.getGrade()), vide(e.getDepartement()),
                    c.prevues, c.emargees, heures(c.minutesEmargees),
                    s[0], s[1],
                    c.enAttente, heures(c.minutesEnAttente),
                    c.nonEmargees, heures(c.minutesNonEmargees),
                    c.aVenir, heures(c.minutesAVenir),
                    c.incoherentes));
        }

        // Les lignes qui demandent un arbitrage remontent : c'est le travail qui reste à faire.
        lignes.sort(Comparator.comparing(LignePaie::estNette)
                .thenComparing(l -> (l.nom() == null ? "" : l.nom()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(l -> (l.prenom() == null ? "" : l.prenom()), String.CASE_INSENSITIVE_ORDER));
        return lignes;
    }

    /**
     * Refuse de calculer hors d'un établissement.
     *
     * <p>Le cloisonnement repose sur le filtre Hibernate, que {@code TenantInterceptor} pose en
     * même temps que ce contexte. Depuis le web, les deux sont toujours là. Mais rien n'empêche
     * demain de brancher cet export sur une tâche planifiée — « envoyer l'état de paie le 28 » —
     * qui, elle, s'exécute sans requête et donc sans filtre : le fichier produit mélangerait
     * alors les enseignants de tous les établissements, et personne ne s'en apercevrait avant
     * qu'un directeur ne lise les salaires du voisin.
     *
     * <p>Une sonde l'a vérifié : sans contexte ni filtre, le calcul rendait bien les lignes de
     * deux établissements, sans lever la moindre erreur. Ce refus ferme la porte maintenant,
     * pendant qu'elle ne coûte rien, plutôt qu'après.
     */
    private static void exigerUnEtablissement() {
        if (TenantContext.get() == null) {
            throw new IllegalStateException(
                    "Export paie appele hors contexte d'etablissement : le cloisonnement ne "
                    + "s'applique pas et le fichier melangerait les etablissements. Poser "
                    + "TenantContext (et le filtre) avant l'appel.");
        }
    }

    /** Totaux de la période, pour l'aperçu à l'écran. */
    public TotauxPaie totaux(List<LignePaie> lignes) {
        double heures = 0, enAttente = 0, nonEmargees = 0, aVenir = 0;
        long enseignants = 0, aArbitrer = 0, incoherentes = 0;
        for (LignePaie l : lignes) {
            heures += l.heures();
            enAttente += l.heuresEnAttente();
            nonEmargees += l.heuresNonEmargees();
            aVenir += l.heuresAVenir();
            incoherentes += l.seancesDureeIncoherente();
            if (l.seancesPrevues() > 0) enseignants++;
            if (!l.estNette()) aArbitrer++;
        }
        return new TotauxPaie(enseignants, aArbitrer, incoherentes, arrondir(heures),
                arrondir(enAttente), arrondir(nonEmargees), arrondir(aVenir));
    }

    public record TotauxPaie(long enseignantsActifs, long lignesAArbitrer, long seancesIncoherentes,
                             double heures, double heuresEnAttente, double heuresNonEmargees,
                             double heuresAVenir) {}

    // ───────────────────────────────── CSV ─────────────────────────────────

    /**
     * CSV à la convention française. Le séparateur décimal est la virgule : dans un Excel
     * francophone, « 27.50 » serait lu comme du texte et la colonne ne s'additionnerait pas.
     * Pour un lecteur qui attend des points, le XLSX ci-dessous ne pose pas la question — ses
     * nombres sont des nombres.
     */
    public byte[] versCsv(List<LignePaie> lignes, YearMonth mois) {
        StringBuilder sb = new StringBuilder();
        sb.append('﻿');   // BOM : sans lui, Excel lit l'UTF-8 comme du Latin-1
        for (int i = 0; i < COLONNES.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(cellule(COLONNES[i]));
        }
        sb.append("\r\n");     // CRLF : RFC 4180, et seule fin de ligne sûre sous Excel

        String periode = mois.toString();
        for (LignePaie l : lignes) {
            sb.append(cellule(l.matricule())).append(';')
              .append(cellule(l.nom())).append(';')
              .append(cellule(l.prenom())).append(';')
              .append(cellule(l.grade())).append(';')
              .append(cellule(l.departement())).append(';')
              .append(cellule(periode)).append(';')
              .append(l.seancesPrevues()).append(';')
              .append(l.seancesEmargees()).append(';')
              .append(nombre(l.heures())).append(';')
              .append(l.retards()).append(';')
              .append(l.horsLigne()).append(';')
              .append(l.seancesEnAttente()).append(';')
              .append(nombre(l.heuresEnAttente())).append(';')
              .append(l.seancesNonEmargees()).append(';')
              .append(nombre(l.heuresNonEmargees())).append(';')
              .append(l.seancesAVenir()).append(';')
              .append(nombre(l.heuresAVenir())).append(';')
              .append(l.seancesDureeIncoherente()).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Neutralise une cellule texte.
     *
     * <p>Un tableur exécute comme une formule toute cellule qui commence par {@code = + - @},
     * une tabulation ou un retour chariot (CWE-1236). Nos noms et matricules viennent d'imports
     * Excel fournis par l'établissement : ils ne sont pas de notre main. Une apostrophe en tête
     * suffit à les faire lire comme du texte, et n'apparaît pas à l'écran.
     */
    static String cellule(String valeur) {
        return '"' + neutraliser(valeur).replace("\"", "\"\"") + '"';
    }

    private static String neutraliser(String valeur) {
        String v = valeur == null ? "" : valeur;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return v;
    }

    private static String nombre(double v) {
        return String.format(Locale.FRANCE, "%.2f", v);
    }

    // ──────────────────────────────── XLSX ─────────────────────────────────

    public byte[] versExcel(List<LignePaie> lignes, YearMonth mois) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet feuille = wb.createSheet("Paie " + mois);

            CellStyle gras = wb.createCellStyle();
            Font police = wb.createFont();
            police.setBold(true);
            gras.setFont(police);

            CellStyle deuxDecimales = wb.createCellStyle();
            deuxDecimales.setDataFormat(wb.createDataFormat().getFormat("0.00"));

            Row entete = feuille.createRow(0);
            for (int i = 0; i < COLONNES.length; i++) {
                Cell c = entete.createCell(i);
                c.setCellValue(COLONNES[i]);
                c.setCellStyle(gras);
            }

            String periode = mois.toString();
            int r = 1;
            for (LignePaie l : lignes) {
                Row row = feuille.createRow(r++);
                row.createCell(0).setCellValue(neutraliser(l.matricule()));
                row.createCell(1).setCellValue(neutraliser(l.nom()));
                row.createCell(2).setCellValue(neutraliser(l.prenom()));
                row.createCell(3).setCellValue(neutraliser(l.grade()));
                row.createCell(4).setCellValue(neutraliser(l.departement()));
                row.createCell(5).setCellValue(periode);
                row.createCell(6).setCellValue(l.seancesPrevues());
                row.createCell(7).setCellValue(l.seancesEmargees());
                decimal(row, COLONNE_HEURES, l.heures(), deuxDecimales);
                row.createCell(9).setCellValue(l.retards());
                row.createCell(10).setCellValue(l.horsLigne());
                row.createCell(11).setCellValue(l.seancesEnAttente());
                decimal(row, 12, l.heuresEnAttente(), deuxDecimales);
                row.createCell(13).setCellValue(l.seancesNonEmargees());
                decimal(row, 14, l.heuresNonEmargees(), deuxDecimales);
                row.createCell(15).setCellValue(l.seancesAVenir());
                decimal(row, 16, l.heuresAVenir(), deuxDecimales);
                row.createCell(17).setCellValue(l.seancesDureeIncoherente());
            }

            // Ligne de total, séparée des données par une ligne vide : le chiffre que le service
            // paie cherche en premier, sans qu'un import automatique la prenne pour un enseignant.
            Row total = feuille.createRow(r + 1);
            Cell libelle = total.createCell(0);
            libelle.setCellValue("TOTAL");
            libelle.setCellStyle(gras);
            Cell cumul = total.createCell(COLONNE_HEURES);
            cumul.setCellValue(totaux(lignes).heures());
            cumul.setCellStyle(deuxDecimales);

            for (int i = 0; i < COLONNES.length; i++) feuille.autoSizeColumn(i);
            feuille.createFreezePane(0, 1);   // l'en-tête reste visible au défilement

            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void decimal(Row row, int colonne, double valeur, CellStyle style) {
        Cell c = row.createCell(colonne);
        c.setCellValue(valeur);
        c.setCellStyle(style);
    }

    // ─────────────────────────────── calcul ────────────────────────────────

    /** Cumul des minutes d'un enseignant sur la période, par sort réservé à chaque séance. */
    private static final class Cumul {
        long prevues, emargees, enAttente, nonEmargees, aVenir, incoherentes;
        long minutesEmargees, minutesEnAttente, minutesNonEmargees, minutesAVenir;

        void ajouter(Seance s, LocalDateTime maintenant) {
            prevues++;
            DureeSeance.Duree d = DureeSeance.de(s);
            if (!d.exploitable()) {
                // Ni payée, ni portée au débit de l'enseignant : personne ne peut rien conclure
                // d'horaires qui ne veulent rien dire. La colonne dédiée le signale.
                incoherentes++;
                return;
            }

            StatutSeance statut = s.getStatut();
            // EN_RETARD est aujourd'hui inutilisé — aucun code ne le pose. S'il revenait, il
            // signifierait « présent, mais arrivé après le début » : une présence, donc des
            // heures dues. Le laisser tomber dans la branche par défaut le compterait comme un
            // oubli d'émargement et retirerait ces heures de la paie.
            if (statut == StatutSeance.EMARGE || statut == StatutSeance.EN_RETARD) {
                emargees++;
                minutesEmargees += d.minutes();
            } else if (statut == StatutSeance.EN_ATTENTE_VALIDATION) {
                enAttente++;
                minutesEnAttente += d.minutes();
            } else if (finit(s, d).isAfter(maintenant)) {
                // Le cours n'a pas encore eu lieu : le compter comme un oubli d'émargement
                // ferait remonter tout l'établissement dès qu'on exporte le mois en cours.
                aVenir++;
                minutesAVenir += d.minutes();
            } else {
                nonEmargees++;
                minutesNonEmargees += d.minutes();
            }
        }
    }

    /** Fin réelle de la séance, le lendemain si elle franchit minuit. */
    private static LocalDateTime finit(Seance s, DureeSeance.Duree d) {
        return LocalDateTime.of(s.getDate(), s.getHeureDebut()).plusMinutes(d.minutes());
    }

    private static double heures(long minutes) {
        return Math.round(minutes * 100.0 / 60.0) / 100.0;
    }

    private static double arrondir(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String vide(String v) {
        return v == null ? "" : v;
    }
}
