package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.repository.ClasseRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.MatiereRepository;
import ci.esatic.sigep.repository.SalleRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Import de l'annuaire enseignants : un fichier dont les colonnes ne correspondent pas
 * aux en-têtes attendus (MATRICULE | NOM | PRENOM | DEPARTEMENT | GRADE) doit être REFUSÉ
 * (avant ce garde-fou, n'importe quel fichier créait des enseignants absurdes).
 */
class ImportServiceEnseignantsTest {

    private EnseignantRepository enseignantRepository;
    private ImportService importService;

    @BeforeEach
    void setUp() {
        enseignantRepository = Mockito.mock(EnseignantRepository.class);
        when(enseignantRepository.existsByMatricule(anyString())).thenReturn(false);
        when(enseignantRepository.save(any(Enseignant.class))).thenAnswer(inv -> inv.getArgument(0));
        importService = new ImportService(
                Mockito.mock(SeanceRepository.class),
                enseignantRepository,
                Mockito.mock(MatiereRepository.class),
                Mockito.mock(ClasseRepository.class),
                Mockito.mock(SalleRepository.class));
    }

    @Test
    void fichierAvecMauvaisesColonnes_estRefuse() {
        // Un fichier de PLANNING chargé par erreur dans l'import d'enseignants.
        MockMultipartFile fichier = xlsx(new String[][]{
                {"DATE", "HEURE_DEBUT", "HEURE_FIN", "MATIERE", "CLASSE", "SALLE"},
                {"06/07/2026", "08:00", "10:00", "Algo", "L3", "B12"}
        });
        assertThatThrownBy(() -> importService.importerEnseignants(fichier))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non conforme")
                .hasMessageContaining("MATRICULE | NOM | PRENOM");
    }

    @Test
    void fichierSansLigneDEntete_estRefuse() {
        MockMultipartFile fichier = xlsx(new String[][]{});
        assertThatThrownBy(() -> importService.importerEnseignants(fichier))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non conforme");
    }

    @Test
    void fichierQuiNestPasUnXlsx_estRefuse() {
        MockMultipartFile fichier = new MockMultipartFile(
                "fichier", "annuaire.xlsx", "text/csv",
                "MATRICULE;NOM;PRENOM\nM1;Doe;John\n".getBytes());
        assertThatThrownBy(() -> importService.importerEnseignants(fichier))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illisible");
    }

    @Test
    void fichierConforme_importeLesLignesValides_etSignaleLesIncompletes() throws Exception {
        MockMultipartFile fichier = xlsx(new String[][]{
                // En-têtes avec accents/casse tolérés
                {"Matricule", "Nom", "Prénom", "Département", "Grade"},
                {"ENS-001", "Kouassi", "Awa", "Informatique", "Assistant"},
                {"ENS-002", "", "Jean", "", ""},              // nom manquant -> invalide (ligne 3)
                {"", "", "", "", ""},                          // vide -> simplement sautée
                {"ENS-003", "Traore", "Moussa", "", ""}
        });
        Map<String, Object> r = importService.importerEnseignants(fichier);
        assertThat(r.get("importes")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Integer> invalides = (List<Integer>) r.get("lignesInvalides");
        assertThat(invalides).containsExactly(3);
    }

    /** Construit un .xlsx en mémoire à partir d'un tableau de lignes. */
    private MockMultipartFile xlsx(String[][] lignes) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            for (int r = 0; r < lignes.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < lignes[r].length; c++) {
                    row.createCell(c).setCellValue(lignes[r][c]);
                }
            }
            wb.write(out);
            return new MockMultipartFile("fichier", "annuaire.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
