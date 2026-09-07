package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Classe;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Matiere;
import ci.esatic.sigep.entity.Salle;
import ci.esatic.sigep.entity.Seance;
import ci.esatic.sigep.repository.ClasseRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.MatiereRepository;
import ci.esatic.sigep.repository.SalleRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Import d'un emploi du temps Excel, avec les types que produisent RÉELLEMENT les tableurs.
 *
 * <p>Incident d'origine : un emploi du temps arrivait troué, et les séances survivantes
 * paraissaient tomber n'importe quel jour. La cause n'était pas la lecture des dates mais celle
 * des HEURES. Excel n'a pas de type « heure » : une heure seule y est une fraction de journée
 * posée sur son époque, le 31/12/1899. Le code prenait la partie DATE de ces cellules, obtenait
 * « 31/12/1899 », le refusait comme heure, et écartait la ligne <b>en silence</b> — l'import
 * continuant sur les suivantes. Ne survivaient que les lignes dont les heures étaient saisies en
 * texte, avec leurs propres dates : d'où l'impression de jours arbitraires.
 *
 * <p>Ces tests mêlent délibérément les types dans un même fichier, comme celui qui a révélé le
 * défaut, et donnent aux cellules de date un format américain — l'affichage n'a aucune raison
 * d'influer sur la valeur lue.
 */
class ImportServicePlanningExcelTest {

    private static final LocalDate LUNDI_7_SEPT = LocalDate.of(2026, 9, 7);
    private static final long USER_ID = 42L;

    private ImportService importService;
    private SeanceRepository seanceRepository;

    @BeforeEach
    void setUp() {
        var matiereRepository = Mockito.mock(MatiereRepository.class);
        var classeRepository = Mockito.mock(ClasseRepository.class);
        var salleRepository = Mockito.mock(SalleRepository.class);
        var enseignantRepository = Mockito.mock(EnseignantRepository.class);
        seanceRepository = Mockito.mock(SeanceRepository.class);

        when(matiereRepository.findAll()).thenReturn(List.of());
        when(classeRepository.findAll()).thenReturn(List.of());
        when(salleRepository.findAll()).thenReturn(List.of());
        when(matiereRepository.save(any(Matiere.class))).thenAnswer(i -> i.getArgument(0));
        when(classeRepository.save(any(Classe.class))).thenAnswer(i -> i.getArgument(0));
        when(salleRepository.save(any(Salle.class))).thenAnswer(i -> i.getArgument(0));

        Enseignant enseignant = Enseignant.builder()
                .id(1L).matricule("ENS-1").nom("Kone").prenom("Ama").build();
        when(enseignantRepository.findByUserId(anyLong())).thenReturn(Optional.of(enseignant));
        when(seanceRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        importService = new ImportService(
                seanceRepository, enseignantRepository,
                matiereRepository, classeRepository, salleRepository,
                Mockito.mock(ci.esatic.sigep.repository.EtablissementRepository.class),
                Mockito.mock(ci.esatic.sigep.tenant.plan.PlanService.class),
                Mockito.mock(ci.esatic.sigep.repository.UserRepository.class),
                Mockito.mock(ci.esatic.sigep.repository.RoleRepository.class),
                Mockito.mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                Mockito.mock(MailService.class));
    }

    @Test
    void heuresExcelReelles_sontLues_etLaLigneNestPasEcartee() throws Exception {
        MockMultipartFile fichier = classeur(new Ligne[] {
                new Ligne(LUNDI_7_SEPT, LocalTime.of(12, 30), LocalTime.of(13, 0), "Bases de données"),
        });

        var resultat = importService.importerMonPlanning(fichier, USER_ID);

        assertThat(resultat.get("totalImporte")).isEqualTo(1);
        assertThat(resultat.get("lignesEnErreur")).isEqualTo(0);

        Seance s = seancesEnregistrees().get(0);
        assertThat(s.getDate()).isEqualTo(LUNDI_7_SEPT);
        assertThat(s.getHeureDebut()).isEqualTo(LocalTime.of(12, 30));
        assertThat(s.getHeureFin()).isEqualTo(LocalTime.of(13, 0));
    }

    @Test
    void typesMelanges_dansUnMemeFichier_aucuneLigneNestPerdue() throws Exception {
        MockMultipartFile fichier = classeur(new Ligne[] {
                new Ligne(LUNDI_7_SEPT, "08:00", "10:00", "Algorithmique"),
                new Ligne(LUNDI_7_SEPT, LocalTime.of(12, 30), LocalTime.of(13, 0), "Bases de données"),
                new Ligne("02/09/2026", "10:15", "12:15", "Structures de données"),
                new Ligne(LUNDI_7_SEPT, LocalTime.of(16, 0), "17:00", "Programmation Java"),
        });

        var resultat = importService.importerMonPlanning(fichier, USER_ID);

        assertThat(resultat.get("lignesEnErreur")).as("aucune ligne ne doit etre ecartee").isEqualTo(0);
        assertThat(resultat.get("totalImporte")).isEqualTo(4);

        // Chaque séance garde SA date, celle du fichier — c'est tout l'enjeu de l'incident.
        assertThat(seancesEnregistrees()).extracting(Seance::getDate).containsExactly(
                LUNDI_7_SEPT, LUNDI_7_SEPT, LocalDate.of(2026, 9, 2), LUNDI_7_SEPT);
        assertThat(seancesEnregistrees()).extracting(Seance::getHeureDebut).containsExactly(
                LocalTime.of(8, 0), LocalTime.of(12, 30), LocalTime.of(10, 15), LocalTime.of(16, 0));
    }

    @Test
    void celluleDateEtHeure_nePerdNiLUneNiLAutre() throws Exception {
        MockMultipartFile fichier = classeur(new Ligne[] {
                new Ligne(LocalDateTime.of(2026, 9, 7, 9, 45),
                          LocalDateTime.of(2026, 9, 7, 9, 45),
                          LocalDateTime.of(2026, 9, 7, 11, 45), "Réseaux"),
        });

        var resultat = importService.importerMonPlanning(fichier, USER_ID);

        assertThat(resultat.get("lignesEnErreur")).isEqualTo(0);
        Seance s = seancesEnregistrees().get(0);
        assertThat(s.getDate()).isEqualTo(LUNDI_7_SEPT);
        assertThat(s.getHeureDebut()).isEqualTo(LocalTime.of(9, 45));
        assertThat(s.getHeureFin()).isEqualTo(LocalTime.of(11, 45));
    }

    // ─── Utilitaires ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Seance> seancesEnregistrees() {
        ArgumentCaptor<List<Seance>> capteur = ArgumentCaptor.forClass(List.class);
        Mockito.verify(seanceRepository).saveAll(capteur.capture());
        return capteur.getValue();
    }

    private record Ligne(Object date, Object debut, Object fin, String matiere) {}

    /** Classeur 6 colonnes : DATE | HEURE_DEBUT | HEURE_FIN | MATIERE | CLASSE | SALLE. */
    private MockMultipartFile classeur(Ligne[] lignes) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Emploi du temps");
            Row entete = sheet.createRow(0);
            String[] noms = {"DATE", "HEURE_DEBUT", "HEURE_FIN", "MATIERE", "CLASSE", "SALLE"};
            for (int c = 0; c < noms.length; c++) entete.createCell(c).setCellValue(noms[c]);

            // Format américain à dessein : la valeur lue ne doit pas dépendre de l'affichage.
            CellStyle styleDate = style(wb, "mm-dd-yy");
            CellStyle styleHeure = style(wb, "h:mm");

            for (int r = 0; r < lignes.length; r++) {
                Row row = sheet.createRow(r + 1);
                poser(row, 0, lignes[r].date(), styleDate, styleHeure);
                poser(row, 1, lignes[r].debut(), styleDate, styleHeure);
                poser(row, 2, lignes[r].fin(), styleDate, styleHeure);
                row.createCell(3).setCellValue(lignes[r].matiere());
                row.createCell(4).setCellValue("L2 Informatique");
                row.createCell(5).setCellValue("Salle A101");
            }
            wb.write(out);
            return new MockMultipartFile("fichier", "edt.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CellStyle style(XSSFWorkbook wb, String format) {
        CellStyle st = wb.createCellStyle();
        st.setDataFormat(wb.createDataFormat().getFormat(format));
        return st;
    }

    private void poser(Row row, int col, Object valeur, CellStyle styleDate, CellStyle styleHeure) {
        var cell = row.createCell(col);
        if (valeur instanceof String texte) {
            cell.setCellValue(texte);
        } else if (valeur instanceof LocalDate d) {
            cell.setCellValue(d);
            cell.setCellStyle(styleDate);
        } else if (valeur instanceof LocalDateTime dt) {
            cell.setCellValue(dt);
            cell.setCellStyle(styleDate);
        } else if (valeur instanceof LocalTime t) {
            // C'est AINSI qu'Excel écrit une heure seule : un nombre entre 0 et 1, fraction de
            // journée, portant un format d'heure. L'écrire comme une date de 1899 produirait un
            // numéro de série négatif — que POI ne reconnaît même pas comme une date, et qui ne
            // reproduirait donc pas le fichier réel.
            cell.setCellValue(t.toSecondOfDay() / 86400.0);
            cell.setCellStyle(styleHeure);
        }
    }
}
