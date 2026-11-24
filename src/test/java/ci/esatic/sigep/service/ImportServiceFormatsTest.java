package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * Chaque établissement produit son emploi du temps à sa façon : c'est le fichier qui s'impose
 * à SIGEP, et non l'inverse.
 */
class ImportServiceFormatsTest {

    private SeanceRepository seances;
    private EnseignantRepository enseignants;
    private ImportService service;
    private Enseignant aya;

    @BeforeEach
    void setUp() {
        seances = Mockito.mock(SeanceRepository.class);
        enseignants = Mockito.mock(EnseignantRepository.class);
        MatiereRepository matieres = Mockito.mock(MatiereRepository.class);
        ClasseRepository classes = Mockito.mock(ClasseRepository.class);
        SalleRepository salles = Mockito.mock(SalleRepository.class);
        Mockito.when(matieres.save(any())).thenAnswer(i -> i.getArgument(0));
        Mockito.when(classes.save(any())).thenAnswer(i -> i.getArgument(0));
        Mockito.when(salles.save(any())).thenAnswer(i -> i.getArgument(0));
        Mockito.when(seances.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        aya = Enseignant.builder().id(7L).matricule("ENS-7").nom("N'Guessan").prenom("Aya")
                .user(User.builder().email("aya@ecole.ci").build()).build();
        Mockito.when(enseignants.findByMatricule(any())).thenReturn(Optional.empty());
        Mockito.when(enseignants.findByMatricule("ENS-7")).thenReturn(Optional.of(aya));
        Mockito.when(enseignants.findAll()).thenReturn(List.of(aya));

        service = new ImportService(seances, enseignants, matieres, classes, salles,
                Mockito.mock(EtablissementRepository.class),
                Mockito.mock(ci.esatic.sigep.tenant.plan.PlanService.class),
                Mockito.mock(UserRepository.class), Mockito.mock(RoleRepository.class),
                Mockito.mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                Mockito.mock(MailService.class),
                org.mockito.Mockito.mock(ci.esatic.sigep.repository.PeriodeCalendrierRepository.class),
                org.mockito.Mockito.mock(CompteEnseignantService.class));
    }

    private static MockMultipartFile csv(String contenu) {
        return new MockMultipartFile("fichier", "edt.csv", "text/csv", contenu.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private List<Seance> enregistrees() {
        ArgumentCaptor<List<Seance>> capture = ArgumentCaptor.forClass(List.class);
        Mockito.verify(seances).saveAll(capture.capture());
        return capture.getValue();
    }

    @Test
    void lesColonnesSeLisentParLeurTitre_dansNimporteQuelOrdre() throws Exception {
        Map<String, Object> r = service.importerPlanning(csv("""
                Local;Intervenant;Cours;Niveau;De;À;Jour
                Amphi C;ENS-7;Droit du numérique;M2;08:00;10:00;03/11/2026
                """));

        assertThat(r.get("totalImporte")).isEqualTo(1);
        Seance s = enregistrees().get(0);
        assertThat(s.getSalle().getLibelle()).isEqualTo("Amphi C");
        assertThat(s.getMatiere().getLibelle()).isEqualTo("Droit du numérique");
        assertThat(s.getClasse().getLibelle()).isEqualTo("M2");
        assertThat(s.getHeureDebut()).isEqualTo(LocalTime.of(8, 0));
        assertThat(s.getDate()).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void lesTitresPeuventVenirApresUnBandeau_etLHoraireTenirDansUneColonne() throws Exception {
        service.importerPlanning(csv("""
                ESATIC - Semestre 1;;;;;
                Date;Horaire;Enseignant;UE;Groupe;Salle
                03/11/2026;8h30 - 10h;Aya N'Guessan;Réseaux;L3 A;B12
                """));

        Seance s = enregistrees().get(0);
        assertThat(s.getHeureDebut()).isEqualTo(LocalTime.of(8, 30));
        assertThat(s.getHeureFin()).isEqualTo(LocalTime.of(10, 0));
        assertThat(s.getEnseignant()).isSameAs(aya);
    }

    @Test
    void lEnseignantSeReconnaitASonMatricule_sonEmail_ouSonNom() throws Exception {
        service.importerPlanning(csv("""
                Date;Début;Fin;Professeur;Matière;Classe;Salle
                03/11/2026;08:00;09:00;ENS-7;A;L1;S1
                04/11/2026;08:00;09:00;aya@ecole.ci;A;L1;S1
                05/11/2026;08:00;09:00;Aya N'Guessan;A;L1;S1
                06/11/2026;08:00;09:00;N'GUESSAN Aya;A;L1;S1
                """));

        assertThat(enregistrees()).hasSize(4).allSatisfy(s -> assertThat(s.getEnseignant()).isSameAs(aya));
    }

    @Test
    void unEnseignantInconnu_estNommeSansPerdreLesAutresLignes() throws Exception {
        Map<String, Object> r = service.importerPlanning(csv("""
                Date;Début;Fin;Professeur;Matière;Classe;Salle
                03/11/2026;08:00;09:00;Inconnu Total;A;L1;S1
                04/11/2026;08:00;09:00;ENS-7;A;L1;S1
                """));

        assertThat(r.get("totalImporte")).isEqualTo(1);
        assertThat((List<String>) r.get("erreurs")).singleElement().asString()
                .startsWith("Ligne 2").contains("Inconnu Total");
    }

    @Test
    void uneColonneIntrouvable_estNommee() {
        assertThatThrownBy(() -> service.importerPlanning(csv("""
                Date;Début;Fin;Professeur;Matière;Classe
                03/11/2026;08:00;09:00;ENS-7;A;L1
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("salle");
    }

    @Test
    void uneDateEnCelluleFusionnee_valaitPourLesLignesSuivantes() throws Exception {
        service.importerPlanning(csv("""
                Date;Début;Fin;Professeur;Matière;Classe;Salle
                03/11/2026;08:00;09:00;ENS-7;A;L1;S1
                ;10:00;11:00;ENS-7;B;L1;S1
                """));

        assertThat(enregistrees()).hasSize(2)
                .allSatisfy(s -> assertThat(s.getDate()).isEqualTo(LocalDate.of(2026, 11, 3)));
    }

    @Test
    void unJourDeLaSemaine_seRepeteSurLaPeriode() throws Exception {
        service.importerPlanning(csv("""
                Jour;Début;Fin;Professeur;Matière;Classe;Salle
                Lundi;08:00;10:00;ENS-7;A;L1;S1
                """), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 16));

        assertThat(enregistrees()).extracting(Seance::getDate).containsExactly(
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 16));
    }

    @Test
    void unJourSansPeriode_estRefuseEnExpliquantPourquoi() throws Exception {
        Map<String, Object> r = service.importerPlanning(csv("""
                Jour;Début;Fin;Professeur;Matière;Classe;Salle
                Lundi;08:00;10:00;ENS-7;A;L1;S1
                """));

        assertThat(r.get("totalImporte")).isEqualTo(0);
        assertThat((List<String>) r.get("erreurs")).singleElement().asString().contains("période");
    }

    @Test
    void reimporterLeMemeFichier_neDedoublePasLesSeances() throws Exception {
        Seance existante = Seance.builder().date(LocalDate.of(2026, 11, 3)).heureDebut(LocalTime.of(8, 0))
                .enseignant(aya).classe(Classe.builder().libelle("L1").build()).build();
        Mockito.when(seances.findByDateBetweenOrderByDateAscHeureDebutAsc(any(), any())).thenReturn(List.of(existante));

        Map<String, Object> r = service.importerPlanning(csv("""
                Date;Début;Fin;Professeur;Matière;Classe;Salle
                03/11/2026;08:00;09:00;ENS-7;A;L1;S1
                03/11/2026;10:00;11:00;ENS-7;A;L1;S1
                """));

        assertThat(r.get("totalImporte")).isEqualTo(1);
        assertThat(r.get("doublonsIgnores")).isEqualTo(1);
    }

    @Test
    void unCsvEnregistreDepuisExcelSousWindows_gardeSesAccents() throws Exception {
        byte[] octets = """
                Date;Début;Fin;Professeur;Matière;Classe;Salle
                03/11/2026;08:00;09:00;ENS-7;"Économie, droit";L1;S1
                """.getBytes(Charset.forName("windows-1252"));

        service.importerPlanning(new MockMultipartFile("fichier", "edt.csv", "text/csv", octets));

        assertThat(enregistrees().get(0).getMatiere().getLibelle()).isEqualTo("Économie, droit");
    }

    @Test
    void unAncienFichierXls_estAccepte() throws Exception {
        try (HSSFWorkbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet f = wb.createSheet();
            Row t = f.createRow(0);
            String[] titres = {"Date", "Début", "Fin", "Professeur", "Matière", "Classe", "Salle"};
            for (int i = 0; i < titres.length; i++) t.createCell(i).setCellValue(titres[i]);
            Row l = f.createRow(1);
            String[] valeurs = {"03/11/2026", "08:00", "09:00", "ENS-7", "A", "L1", "S1"};
            for (int i = 0; i < valeurs.length; i++) l.createCell(i).setCellValue(valeurs[i]);
            wb.write(out);

            Map<String, Object> r = service.importerPlanning(new MockMultipartFile("fichier", "edt.xls",
                    "application/vnd.ms-excel", out.toByteArray()));

            assertThat(r.get("totalImporte")).isEqualTo(1);
        }
    }

    @Test
    void unFichierSansTitreReconnu_garderLaLectureHistorique() throws Exception {
        Map<String, Object> r = service.importerPlanning(csv("""
                a;b;c;d;e;f;g
                03/11/2026;08:00;10:00;ENS-7;Algorithmique;L2;B12
                """));

        assertThat(r.get("totalImporte")).isEqualTo(1);
        assertThat(enregistrees().get(0).getMatiere().getLibelle()).isEqualTo("Algorithmique");
    }

    @Test
    void lesEcrituresCourantesDesHeuresEtDesDates_sontComprises() throws Exception {
        service.importerPlanning(csv("""
                Date;Début;Fin;Professeur;Matière;Classe;Salle
                Lundi 2/11/2026;8h;9.30;ENS-7;A;L1;S1
                3 novembre 2026;08H00;10H00;ENS-7;B;L1;S1
                """));

        List<Seance> s = enregistrees();
        assertThat(s.get(0).getDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(s.get(0).getHeureFin()).isEqualTo(LocalTime.of(9, 30));
        assertThat(s.get(1).getDate()).isEqualTo(LocalDate.of(2026, 11, 3));
    }
}
