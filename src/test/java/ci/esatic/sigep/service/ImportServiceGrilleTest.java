package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

/** L'emploi du temps le plus répandu : les jours en colonnes, les créneaux en lignes. */
class ImportServiceGrilleTest {

    private static final LocalDate LUNDI = LocalDate.of(2026, 11, 2);

    private SeanceRepository seances;
    private PeriodeCalendrierRepository periodes;
    private ImportService service;
    private Enseignant aya;

    @BeforeEach
    void setUp() {
        seances = Mockito.mock(SeanceRepository.class);
        EnseignantRepository enseignants = Mockito.mock(EnseignantRepository.class);
        MatiereRepository matieres = Mockito.mock(MatiereRepository.class);
        ClasseRepository classes = Mockito.mock(ClasseRepository.class);
        SalleRepository salles = Mockito.mock(SalleRepository.class);
        periodes = Mockito.mock(PeriodeCalendrierRepository.class);
        Mockito.when(matieres.save(any())).thenAnswer(i -> i.getArgument(0));
        Mockito.when(classes.save(any())).thenAnswer(i -> i.getArgument(0));
        Mockito.when(salles.save(any())).thenAnswer(i -> i.getArgument(0));
        Mockito.when(seances.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        aya = Enseignant.builder().id(7L).matricule("ENS-7").nom("Kouassi").prenom("Aya")
                .user(User.builder().email("aya@ecole.ci").build()).build();
        Mockito.when(enseignants.findByMatricule(any())).thenReturn(Optional.empty());
        Mockito.when(enseignants.findAll()).thenReturn(List.of(aya));

        service = new ImportService(seances, enseignants, matieres, classes, salles,
                Mockito.mock(EtablissementRepository.class),
                Mockito.mock(ci.esatic.sigep.tenant.plan.PlanService.class),
                Mockito.mock(UserRepository.class), Mockito.mock(RoleRepository.class),
                Mockito.mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                Mockito.mock(MailService.class), periodes, Mockito.mock(CompteEnseignantService.class));
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
    void chaqueCaseDevientUneSeance_surLaPeriode() throws Exception {
        Map<String, Object> r = service.importerPlanning(csv("""
                Classe : L3 Info;;
                Créneau;Lundi;Mardi
                08h00-10h00;Réseaux - M. Kouassi - B12;
                10h-12h;;Salle B14 - Algorithmique - Aya Kouassi
                Pause;;
                """), LUNDI, LUNDI.plusDays(7));

        assertThat(r.get("totalImporte")).isEqualTo(3);   // Lundi 2 et 9, mardi 3
        List<Seance> s = enregistrees();
        Seance reseaux = s.get(0);
        assertThat(reseaux.getDate()).isEqualTo(LUNDI);
        assertThat(reseaux.getHeureDebut()).isEqualTo(LocalTime.of(8, 0));
        assertThat(reseaux.getHeureFin()).isEqualTo(LocalTime.of(10, 0));
        assertThat(reseaux.getMatiere().getLibelle()).isEqualTo("Réseaux");
        assertThat(reseaux.getSalle().getLibelle()).isEqualTo("B12");
        assertThat(reseaux.getClasse().getLibelle()).isEqualTo("L3 Info");
        assertThat(reseaux.getEnseignant()).isSameAs(aya);
        assertThat(s).filteredOn(x -> x.getMatiere().getLibelle().equals("Algorithmique"))
                .singleElement().satisfies(x -> assertThat(x.getSalle().getLibelle()).isEqualTo("B14"));
    }

    @Test
    void lesJoursPeuventPorterLeurDate_sansPeriode() throws Exception {
        service.importerPlanning(csv("""
                Horaire;Lundi 02/11/2026;Mardi 03/11/2026
                08:00 - 10:00;Réseaux / Kouassi / B12;
                """), null, null, "L3");

        assertThat(enregistrees()).singleElement().satisfies(s -> {
            assertThat(s.getDate()).isEqualTo(LUNDI);
            assertThat(s.getClasse().getLibelle()).isEqualTo("L3");
        });
    }

    @Test
    void lesJoursEnLignes_etLesCreneauxEnColonnes_seLisentAussi() throws Exception {
        service.importerPlanning(csv("""
                ;08h-10h;10h-12h
                Lundi;Réseaux - Kouassi - B12;
                Mardi;;Algo - Kouassi - B14
                """), LUNDI, LUNDI.plusDays(6), "L3");

        List<Seance> s = enregistrees();
        assertThat(s).hasSize(2);
        assertThat(s).filteredOn(x -> x.getMatiere().getLibelle().equals("Algo")).singleElement()
                .satisfies(x -> {
                    assertThat(x.getDate()).isEqualTo(LUNDI.plusDays(1));
                    assertThat(x.getHeureDebut()).isEqualTo(LocalTime.of(10, 0));
                });
    }

    @Test
    void uneCaseIllisible_estNommeeSansPerdreLesAutres() throws Exception {
        Map<String, Object> r = service.importerPlanning(csv("""
                Créneau;Lundi;Mardi
                08h-10h;Réseaux - Inconnu - B12;Algo - Kouassi - B14
                """), LUNDI, LUNDI.plusDays(6), "L3");

        assertThat(r.get("totalImporte")).isEqualTo(1);
        assertThat((List<String>) r.get("erreurs")).singleElement().asString()
                .contains("Lundi").contains("enseignant").contains("Inconnu");
    }

    @Test
    void uneGrilleSansClasse_estExpliquee() throws Exception {
        Map<String, Object> r = service.importerPlanning(csv("""
                Créneau;Lundi;Mardi
                08h-10h;Réseaux - Kouassi - B12;
                """), LUNDI, LUNDI.plusDays(6));

        assertThat(r.get("totalImporte")).isEqualTo(0);
        assertThat((List<String>) r.get("erreurs")).singleElement().asString().contains("classe");
    }

    @Test
    void unJourFerie_nestPasPlanifie_etEstCompte() throws Exception {
        Mockito.when(periodes.findAllByOrderByDebutAsc()).thenReturn(List.of(PeriodeCalendrier.builder()
                .type(TypePeriode.FERIE).libelle("Férié").debut(LUNDI).fin(LUNDI).build()));

        Map<String, Object> r = service.importerPlanning(csv("""
                Créneau;Lundi;Mardi
                08h-10h;Réseaux - Kouassi - B12;
                """), LUNDI, LUNDI.plusDays(7), "L3");

        assertThat(r.get("joursFermesIgnores")).isEqualTo(1);
        assertThat(enregistrees()).extracting(Seance::getDate).containsExactly(LUNDI.plusDays(7));
    }

    @Test
    void chaqueFeuilleEstUneClasse_etUneCaseFusionneeDureTousSesCreneaux() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String classe : List.of("L1", "L2")) {
                Sheet f = wb.createSheet(classe);
                Row t = f.createRow(0);
                t.createCell(0).setCellValue("Créneau");
                t.createCell(1).setCellValue("Lundi");
                t.createCell(2).setCellValue("Mardi");
                Row l1 = f.createRow(1);
                l1.createCell(0).setCellValue("08h-10h");
                l1.createCell(1).setCellValue("Réseaux\nKouassi\nB12");
                f.createRow(2).createCell(0).setCellValue("10h-12h");
                f.addMergedRegion(new CellRangeAddress(1, 2, 1, 1));
            }
            wb.write(out);

            service.importerPlanning(new MockMultipartFile("fichier", "edt.xlsx", "application/octet-stream",
                    out.toByteArray()), LUNDI, LUNDI.plusDays(6));
        }

        List<Seance> s = enregistrees();
        assertThat(s).hasSize(2);
        assertThat(s).extracting(x -> x.getClasse().getLibelle()).containsExactly("L1", "L2");
        assertThat(s).allSatisfy(x -> assertThat(x.getHeureFin()).isEqualTo(LocalTime.of(12, 0)));
    }
}
