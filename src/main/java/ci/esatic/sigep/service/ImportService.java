package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final SeanceRepository seanceRepository;
    private final EnseignantRepository enseignantRepository;
    private final MatiereRepository matiereRepository;
    private final ClasseRepository classeRepository;
    private final SalleRepository salleRepository;

    // Import admin : fichier 7 colonnes avec MATRICULE_ENSEIGNANT
    @Transactional
    public Map<String, Object> importerPlanning(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        List<Seance> seances = filename != null && filename.endsWith(".csv")
                ? importerCSV(file)
                : importerExcel(file);

        List<Seance> saved = seanceRepository.saveAll(seances);
        log.info("Import admin : {} seances importees", saved.size());

        Map<String, Object> result = new HashMap<>();
        result.put("totalImporte", saved.size());
        result.put("fichier", filename);
        return result;
    }

    // Import admin d'enseignants (annuaire) : crée les profils SANS compte (user=null, statut PENDING).
    // Les enseignants s'inscrivent ensuite eux-mêmes via leur matricule, puis l'admin valide.
    // Colonnes : MATRICULE | NOM | PRENOM | DEPARTEMENT | GRADE
    @Transactional
    public Map<String, Object> importerEnseignants(MultipartFile file) throws Exception {
        int importes = 0, ignores = 0;
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String matricule = getCellValue(row, 0);
                if (matricule.isBlank()) continue;
                if (enseignantRepository.existsByMatricule(matricule)) { ignores++; continue; }
                Enseignant e = Enseignant.builder()
                        .matricule(matricule)
                        .nom(getCellValue(row, 1))
                        .prenom(getCellValue(row, 2))
                        .departement(emptyToNull(getCellValue(row, 3)))
                        .grade(emptyToNull(getCellValue(row, 4)))
                        .build();
                enseignantRepository.save(e);
                importes++;
            }
        }
        log.info("Import enseignants : {} crees, {} ignores (matricule deja existant)", importes, ignores);
        Map<String, Object> result = new HashMap<>();
        result.put("importes", importes);
        result.put("ignores", ignores);
        return result;
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // Import enseignant : fichier 6 colonnes sans MATRICULE (enseignant = utilisateur connecté)
    // Colonnes : DATE | HEURE_DEBUT | HEURE_FIN | MATIERE | CLASSE | SALLE
    @Transactional
    public Map<String, Object> importerMonPlanning(MultipartFile file, Long userId) throws Exception {
        Enseignant enseignant = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Profil enseignant introuvable"));

        String filename = file.getOriginalFilename();
        List<Seance> seances = filename != null && filename.endsWith(".csv")
                ? importerCSVEnseignant(file, enseignant)
                : importerExcelEnseignant(file, enseignant);

        List<Seance> saved = seanceRepository.saveAll(seances);
        log.info("Import enseignant {} : {} seances importees", enseignant.getMatricule(), saved.size());

        Map<String, Object> result = new HashMap<>();
        result.put("totalImporte", saved.size());
        result.put("fichier", filename);
        result.put("enseignant", enseignant.getPrenom() + " " + enseignant.getNom());
        return result;
    }

    private List<Seance> importerExcel(MultipartFile file) throws Exception {
        List<Seance> seances = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            // Ligne 0 = en-têtes, on commence à 1
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                try {
                    Seance seance = parseRowExcel(row);
                    if (seance != null) seances.add(seance);
                } catch (Exception e) {
                    log.warn("Ligne {} ignoree : {}", i + 1, e.getMessage());
                }
            }
        }
        return seances;
    }

    private Seance parseRowExcel(Row row) {
        // Colonnes attendues : DATE | HEURE_DEBUT | HEURE_FIN | MATRICULE_ENSEIGNANT | MATIERE | CLASSE | SALLE
        String dateStr = getCellValue(row, 0);
        String heureDebutStr = getCellValue(row, 1);
        String heureFinStr = getCellValue(row, 2);
        String matricule = getCellValue(row, 3);
        String libelleMatiere = getCellValue(row, 4);
        String libelleClasse = getCellValue(row, 5);
        String libelleSalle = getCellValue(row, 6);

        if (dateStr.isBlank() || matricule.isBlank()) return null;

        Enseignant enseignant = enseignantRepository.findByMatricule(matricule)
                .orElseThrow(() -> new IllegalArgumentException("Enseignant introuvable : " + matricule));
        Matiere matiere = matiereRepository.findByLibelleIgnoreCase(libelleMatiere)
                .orElseThrow(() -> new IllegalArgumentException("Matiere introuvable : " + libelleMatiere));
        Classe classe = classeRepository.findByLibelleIgnoreCase(libelleClasse)
                .orElseThrow(() -> new IllegalArgumentException("Classe introuvable : " + libelleClasse));
        Salle salle = salleRepository.findByLibelleIgnoreCase(libelleSalle)
                .orElseThrow(() -> new IllegalArgumentException("Salle introuvable : " + libelleSalle));

        return Seance.builder()
                .date(LocalDate.parse(dateStr, DATE_FMT))
                .heureDebut(LocalTime.parse(heureDebutStr, TIME_FMT))
                .heureFin(LocalTime.parse(heureFinStr, TIME_FMT))
                .enseignant(enseignant)
                .matiere(matiere)
                .classe(classe)
                .salle(salle)
                .type(TypeSeance.NORMALE)
                .statut(StatutSeance.A_FAIRE)
                .build();
    }

    private List<Seance> importerCSV(MultipartFile file) throws Exception {
        List<Seance> seances = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; } // skip header
                String[] cols = line.split(";");
                if (cols.length < 7) continue;
                try {
                    String matricule = cols[3].trim();
                    Enseignant enseignant = enseignantRepository.findByMatricule(matricule)
                            .orElseThrow(() -> new IllegalArgumentException("Enseignant : " + matricule));
                    Matiere matiere = matiereRepository.findByLibelleIgnoreCase(cols[4].trim())
                            .orElseThrow(() -> new IllegalArgumentException("Matiere : " + cols[4].trim()));
                    Classe classe = classeRepository.findByLibelleIgnoreCase(cols[5].trim())
                            .orElseThrow(() -> new IllegalArgumentException("Classe : " + cols[5].trim()));
                    Salle salle = salleRepository.findByLibelleIgnoreCase(cols[6].trim())
                            .orElseThrow(() -> new IllegalArgumentException("Salle : " + cols[6].trim()));

                    seances.add(Seance.builder()
                            .date(LocalDate.parse(cols[0].trim(), DATE_FMT))
                            .heureDebut(LocalTime.parse(cols[1].trim(), TIME_FMT))
                            .heureFin(LocalTime.parse(cols[2].trim(), TIME_FMT))
                            .enseignant(enseignant).matiere(matiere).classe(classe).salle(salle)
                            .type(TypeSeance.NORMALE).statut(StatutSeance.A_FAIRE)
                            .build());
                } catch (Exception e) {
                    log.warn("Ligne CSV ignoree : {}", e.getMessage());
                }
            }
        }
        return seances;
    }

    // --- Méthodes pour import enseignant (6 colonnes, sans matricule) ---

    private List<Seance> importerExcelEnseignant(MultipartFile file, Enseignant enseignant) throws Exception {
        List<Seance> seances = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                try {
                    Seance s = parseRowExcelEnseignant(row, enseignant);
                    if (s != null) seances.add(s);
                } catch (Exception e) {
                    log.warn("Ligne {} ignoree : {}", i + 1, e.getMessage());
                }
            }
        }
        return seances;
    }

    private Seance parseRowExcelEnseignant(Row row, Enseignant enseignant) {
        // Colonnes : DATE | HEURE_DEBUT | HEURE_FIN | MATIERE | CLASSE | SALLE
        String dateStr      = getCellValue(row, 0);
        String heureDebutStr = getCellValue(row, 1);
        String heureFinStr  = getCellValue(row, 2);
        String libelleMatiere = getCellValue(row, 3);
        String libelleClasse  = getCellValue(row, 4);
        String libelleSalle   = getCellValue(row, 5);

        if (dateStr.isBlank()) return null;

        Matiere matiere = matiereRepository.findByLibelleIgnoreCase(libelleMatiere)
                .orElseThrow(() -> new IllegalArgumentException("Matiere introuvable : " + libelleMatiere));
        Classe classe = classeRepository.findByLibelleIgnoreCase(libelleClasse)
                .orElseThrow(() -> new IllegalArgumentException("Classe introuvable : " + libelleClasse));
        Salle salle = salleRepository.findByLibelleIgnoreCase(libelleSalle)
                .orElseThrow(() -> new IllegalArgumentException("Salle introuvable : " + libelleSalle));

        return Seance.builder()
                .date(LocalDate.parse(dateStr, DATE_FMT))
                .heureDebut(LocalTime.parse(heureDebutStr, TIME_FMT))
                .heureFin(LocalTime.parse(heureFinStr, TIME_FMT))
                .enseignant(enseignant)
                .matiere(matiere)
                .classe(classe)
                .salle(salle)
                .type(TypeSeance.NORMALE)
                .statut(StatutSeance.A_FAIRE)
                .build();
    }

    private List<Seance> importerCSVEnseignant(MultipartFile file, Enseignant enseignant) throws Exception {
        List<Seance> seances = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }
                String[] cols = line.split(";");
                if (cols.length < 6) continue;
                try {
                    Matiere matiere = matiereRepository.findByLibelleIgnoreCase(cols[3].trim())
                            .orElseThrow(() -> new IllegalArgumentException("Matiere : " + cols[3].trim()));
                    Classe classe = classeRepository.findByLibelleIgnoreCase(cols[4].trim())
                            .orElseThrow(() -> new IllegalArgumentException("Classe : " + cols[4].trim()));
                    Salle salle = salleRepository.findByLibelleIgnoreCase(cols[5].trim())
                            .orElseThrow(() -> new IllegalArgumentException("Salle : " + cols[5].trim()));

                    seances.add(Seance.builder()
                            .date(LocalDate.parse(cols[0].trim(), DATE_FMT))
                            .heureDebut(LocalTime.parse(cols[1].trim(), TIME_FMT))
                            .heureFin(LocalTime.parse(cols[2].trim(), TIME_FMT))
                            .enseignant(enseignant).matiere(matiere).classe(classe).salle(salle)
                            .type(TypeSeance.NORMALE).statut(StatutSeance.A_FAIRE)
                            .build());
                } catch (Exception e) {
                    log.warn("Ligne CSV enseignant ignoree : {}", e.getMessage());
                }
            }
        }
        return seances;
    }

    private String getCellValue(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().format(DATE_FMT);
                }
                yield String.valueOf((long) cell.getNumericCellValue());
            }
            default -> "";
        };
    }
}
