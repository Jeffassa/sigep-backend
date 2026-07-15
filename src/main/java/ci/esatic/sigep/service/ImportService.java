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
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // E5 : plusieurs formats acceptés en entrée (l'établissement produit son propre EDT).
    private static final List<DateTimeFormatter> DATE_FMTS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"));
    private static final List<DateTimeFormatter> TIME_FMTS = List.of(
            DateTimeFormatter.ofPattern("HH:mm"),
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("HH'h'mm"),
            DateTimeFormatter.ofPattern("HH'h'"),
            DateTimeFormatter.ofPattern("HH"));

    private final SeanceRepository seanceRepository;
    private final EnseignantRepository enseignantRepository;
    private final MatiereRepository matiereRepository;
    private final ClasseRepository classeRepository;
    private final SalleRepository salleRepository;
    private final ci.esatic.sigep.repository.EtablissementRepository etablissementRepository;
    private final ci.esatic.sigep.tenant.plan.PlanService planService;

    // ─────────────────────────────────────────────────────────────────────────
    // Import admin : fichier 7 colonnes avec MATRICULE_ENSEIGNANT
    //   DATE | HEURE_DEBUT | HEURE_FIN | MATRICULE_ENSEIGNANT | MATIERE | CLASSE | SALLE
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public Map<String, Object> importerPlanning(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        ContexteImport ctx = new ContexteImport();  // caches référentiels + erreurs (E3/E4/C6)

        List<Seance> seances = estCsv(filename)
                ? importerPlanningCsv(file, ctx, null)
                : importerPlanningExcel(file, ctx, null);

        List<Seance> saved = seanceRepository.saveAll(seances);
        log.info("Import admin : {} séances importées, {} ligne(s) en erreur", saved.size(), ctx.erreurs.size());
        return resultat(filename, saved.size(), ctx, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Import enseignant : fichier 6 colonnes SANS matricule (enseignant = connecté)
    //   DATE | HEURE_DEBUT | HEURE_FIN | MATIERE | CLASSE | SALLE
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public Map<String, Object> importerMonPlanning(MultipartFile file, Long userId) throws Exception {
        Enseignant enseignant = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Profil enseignant introuvable"));

        String filename = file.getOriginalFilename();
        ContexteImport ctx = new ContexteImport();

        List<Seance> seances = estCsv(filename)
                ? importerPlanningCsv(file, ctx, enseignant)
                : importerPlanningExcel(file, ctx, enseignant);

        List<Seance> saved = seanceRepository.saveAll(seances);
        log.info("Import enseignant {} : {} séances importées, {} ligne(s) en erreur",
                enseignant.getMatricule(), saved.size(), ctx.erreurs.size());
        return resultat(filename, saved.size(), ctx, enseignant.getPrenom() + " " + enseignant.getNom());
    }

    /** Résultat d'import homogène (E3) : totaux + détail des lignes en erreur. */
    private Map<String, Object> resultat(String filename, int importes, ContexteImport ctx, String enseignant) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalImporte", importes);        // clé consommée par le mobile (ImportResponse)
        result.put("fichier", filename);
        if (enseignant != null) result.put("enseignant", enseignant);
        result.put("lignesEnErreur", ctx.erreurs.size());
        result.put("erreurs", ctx.erreurs);          // liste "Ligne N : motif" (affichable à l'admin)
        result.put("referentielsCrees", ctx.referentielsCrees);
        return result;
    }

    // ─── Parsing Excel ─────────────────────────────────────────────────────────
    // enseignantFixe != null → fichier 6 colonnes (l'enseignant est imposé).
    private List<Seance> importerPlanningExcel(MultipartFile file, ContexteImport ctx, Enseignant enseignantFixe) throws Exception {
        List<Seance> seances = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {   // ligne 0 = en-têtes
                Row row = sheet.getRow(i);
                if (row == null) continue;
                int numLigne = i + 1;
                try {
                    String[] cols = colonnesExcel(row, enseignantFixe == null ? 7 : 6);
                    if (ligneVide(cols)) continue;
                    Seance s = construireSeance(cols, ctx, enseignantFixe, numLigne);
                    if (s != null) seances.add(s);
                } catch (Exception e) {
                    ctx.erreur(numLigne, e.getMessage());
                }
            }
        }
        return seances;
    }

    // ─── Parsing CSV ─────────────────────────────────────────────────────────
    private List<Seance> importerPlanningCsv(MultipartFile file, ContexteImport ctx, Enseignant enseignantFixe) throws Exception {
        List<Seance> seances = new ArrayList<>();
        int nbColonnes = enseignantFixe == null ? 7 : 6;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            char sep = 0;
            int numLigne = 0;
            boolean premiere = true;
            while ((line = reader.readLine()) != null) {
                numLigne++;
                if (premiere) { sep = detecterSeparateur(line); premiere = false; continue; } // saute l'en-tête
                if (line.isBlank()) continue;
                try {
                    String[] cols = decouper(line, sep, nbColonnes);
                    if (ligneVide(cols)) continue;
                    Seance s = construireSeance(cols, ctx, enseignantFixe, numLigne);
                    if (s != null) seances.add(s);
                } catch (Exception e) {
                    ctx.erreur(numLigne, e.getMessage());
                }
            }
        }
        return seances;
    }

    /**
     * Construit une séance à partir d'une ligne normalisée (upsert des référentiels — C6).
     * cols (7) : DATE|HEURE_DEBUT|HEURE_FIN|MATRICULE|MATIERE|CLASSE|SALLE
     * cols (6) : DATE|HEURE_DEBUT|HEURE_FIN|MATIERE|CLASSE|SALLE (enseignant imposé)
     */
    private Seance construireSeance(String[] cols, ContexteImport ctx, Enseignant enseignantFixe, int numLigne) {
        int decalage = (enseignantFixe == null) ? 1 : 0;   // colonne matricule seulement en mode admin
        String dateStr  = cols[0];
        String hDebut   = cols[1];
        String hFin     = cols[2];
        String matiere  = cols[3 + decalage];
        String classe   = cols[4 + decalage];
        String salle    = cols[5 + decalage];

        if (isBlank(dateStr)) return null;

        Enseignant enseignant = enseignantFixe;
        if (enseignant == null) {
            String matricule = cols[3];
            if (isBlank(matricule)) return null;
            enseignant = enseignantRepository.findByMatricule(matricule.trim())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "matricule enseignant inconnu « " + matricule.trim() + " » (importez d'abord l'annuaire)"));
        }

        return Seance.builder()
                .date(parseDate(dateStr))
                .heureDebut(parseTime(hDebut))
                .heureFin(parseTime(hFin))
                .enseignant(enseignant)
                .matiere(ctx.upsertMatiere(exigerLibelle(matiere, "matière")))
                .classe(ctx.upsertClasse(exigerLibelle(classe, "classe")))
                .salle(ctx.upsertSalle(exigerLibelle(salle, "salle")))
                .type(TypeSeance.NORMALE)
                .statut(StatutSeance.A_FAIRE)
                .build();
    }

    // ─── Import d'annuaire enseignants (inchangé sur le fond) ──────────────────
    private static final String[] COLONNES_ENSEIGNANTS = {"MATRICULE", "NOM", "PRENOM", "DEPARTEMENT", "GRADE"};

    @Transactional
    public Map<String, Object> importerEnseignants(MultipartFile file) throws Exception {
        int importes = 0, ignores = 0;
        List<Integer> lignesInvalides = new ArrayList<>();
        Workbook workbook;
        try {
            workbook = new XSSFWorkbook(file.getInputStream());
        } catch (Exception ex) {
            throw new IllegalArgumentException("fichier illisible — un fichier Excel (.xlsx) est attendu.");
        }
        Long tenantId = ci.esatic.sigep.tenant.TenantContext.get();
        Etablissement tenant = tenantId == null ? null
                : etablissementRepository.findById(tenantId).orElse(null);
        long existants = tenant == null ? 0 : enseignantRepository.countByEtablissementId(tenant.getId());
        boolean quotaAtteint = false;

        try (workbook) {
            Sheet sheet = workbook.getSheetAt(0);
            verifierEnteteEnseignants(sheet);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String matricule = getCellValue(row, 0);
                String nom = getCellValue(row, 1);
                String prenom = getCellValue(row, 2);
                if (matricule.isBlank() && nom.isBlank() && prenom.isBlank()) continue;
                if (matricule.isBlank() || nom.isBlank() || prenom.isBlank()) {
                    lignesInvalides.add(i + 1);
                    continue;
                }
                if (enseignantRepository.existsByMatricule(matricule)) { ignores++; continue; }
                if (tenant != null && planService.quotaEnseignantsAtteint(tenant, existants + importes)) {
                    quotaAtteint = true;
                    break;
                }
                Enseignant e = Enseignant.builder()
                        .matricule(matricule)
                        .nom(nom)
                        .prenom(prenom)
                        .departement(emptyToNull(getCellValue(row, 3)))
                        .grade(emptyToNull(getCellValue(row, 4)))
                        .build();
                enseignantRepository.save(e);
                importes++;
            }
        }
        log.info("Import enseignants : {} créés, {} ignorés (matricule existant), {} ligne(s) invalide(s) {}, quotaAtteint={}",
                importes, ignores, lignesInvalides.size(), lignesInvalides, quotaAtteint);
        Map<String, Object> result = new HashMap<>();
        result.put("importes", importes);
        result.put("ignores", ignores);
        result.put("lignesInvalides", lignesInvalides);
        result.put("quotaAtteint", quotaAtteint);
        return result;
    }

    private void verifierEnteteEnseignants(Sheet sheet) {
        String attendu = String.join(" | ", COLONNES_ENSEIGNANTS);
        Row entete = sheet.getRow(0);
        if (entete == null) {
            throw new IllegalArgumentException("fichier non conforme — 1re ligne d'en-têtes absente. "
                    + "Colonnes attendues : " + attendu + ".");
        }
        for (int c = 0; c < COLONNES_ENSEIGNANTS.length; c++) {
            String trouve = normaliserEntete(getCellValue(entete, c));
            boolean obligatoire = c < 3;
            if ((obligatoire && !trouve.equals(COLONNES_ENSEIGNANTS[c]))
                    || (!obligatoire && !trouve.isEmpty() && !trouve.equals(COLONNES_ENSEIGNANTS[c]))) {
                throw new IllegalArgumentException("fichier non conforme — colonne " + (c + 1)
                        + " : « " + getCellValue(entete, c) + " » au lieu de « " + COLONNES_ENSEIGNANTS[c]
                        + " ». Colonnes attendues : " + attendu + ".");
            }
        }
    }

    private String normaliserEntete(String s) {
        if (s == null) return "";
        String sansAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sansAccents.toUpperCase(Locale.ROOT).replaceAll("[^A-Z_]", "");
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // ─── Helpers de parsing / normalisation ────────────────────────────────────

    private boolean estCsv(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private boolean ligneVide(String[] cols) {
        for (String c : cols) if (c != null && !c.isBlank()) return false;
        return true;
    }

    private String exigerLibelle(String v, String champ) {
        if (isBlank(v)) throw new IllegalArgumentException("colonne " + champ + " vide");
        return v.trim();
    }

    /** Détecte le séparateur CSV le plus probable (E5) : ';', tabulation ou ','. */
    private char detecterSeparateur(String enteteLine) {
        if (enteteLine == null) return ';';
        long pv = enteteLine.chars().filter(c -> c == ';').count();
        long tab = enteteLine.chars().filter(c -> c == '\t').count();
        long virg = enteteLine.chars().filter(c -> c == ',').count();
        if (pv >= tab && pv >= virg) return ';';
        if (tab >= virg) return '\t';
        return ',';
    }

    private String[] decouper(String line, char sep, int nbColonnes) {
        String[] parts = line.split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1);
        if (parts.length < nbColonnes) {
            throw new IllegalArgumentException("ligne incomplète (" + parts.length + " colonnes sur " + nbColonnes + " attendues)");
        }
        String[] out = new String[nbColonnes];
        for (int i = 0; i < nbColonnes; i++) out[i] = parts[i] == null ? "" : parts[i].trim();
        return out;
    }

    private String[] colonnesExcel(Row row, int nbColonnes) {
        String[] out = new String[nbColonnes];
        for (int i = 0; i < nbColonnes; i++) out[i] = getCellValue(row, i);
        return out;
    }

    private LocalDate parseDate(String s) {
        String v = s.trim();
        for (DateTimeFormatter f : DATE_FMTS) {
            try { return LocalDate.parse(v, f); } catch (Exception ignore) { /* format suivant */ }
        }
        throw new IllegalArgumentException("date invalide « " + v + " » (attendu jj/mm/aaaa)");
    }

    private LocalTime parseTime(String s) {
        String v = s.trim().replace(" ", "");
        for (DateTimeFormatter f : TIME_FMTS) {
            try { return LocalTime.parse(v, f); } catch (Exception ignore) { /* format suivant */ }
        }
        throw new IllegalArgumentException("heure invalide « " + v + " » (attendu HH:mm)");
    }

    /** Clé de rapprochement tolérante (E4) : sans accents, espaces compactés, minuscule. */
    private static String cle(String libelle) {
        if (libelle == null) return "";
        String sansAccents = Normalizer.normalize(libelle, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sansAccents.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
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
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue().trim(); }
                catch (Exception e) { yield String.valueOf((long) cell.getNumericCellValue()); }
            }
            default -> "";
        };
    }

    /**
     * Contexte d'un import : caches normalisés des référentiels du tenant + upsert (C6/E4).
     * Les entités créées sont estampillées avec l'établissement courant par TenantListener.
     */
    private class ContexteImport {
        final Map<String, Matiere> matieres = charger(matiereRepository.findAll(), Matiere::getLibelle);
        final Map<String, Classe> classes = charger(classeRepository.findAll(), Classe::getLibelle);
        final Map<String, Salle> salles = charger(salleRepository.findAll(), Salle::getLibelle);
        final List<String> erreurs = new ArrayList<>();
        int referentielsCrees = 0;

        <T> Map<String, T> charger(List<T> liste, Function<T, String> libelle) {
            Map<String, T> m = new HashMap<>();
            for (T t : liste) m.putIfAbsent(cle(libelle.apply(t)), t);
            return m;
        }

        Matiere upsertMatiere(String libelle) {
            return matieres.computeIfAbsent(cle(libelle), k -> {
                referentielsCrees++;
                return matiereRepository.save(Matiere.builder().libelle(libelle).build());
            });
        }

        Classe upsertClasse(String libelle) {
            return classes.computeIfAbsent(cle(libelle), k -> {
                referentielsCrees++;
                return classeRepository.save(Classe.builder().libelle(libelle).build());
            });
        }

        Salle upsertSalle(String libelle) {
            return salles.computeIfAbsent(cle(libelle), k -> {
                referentielsCrees++;
                return salleRepository.save(Salle.builder().libelle(libelle).build());
            });
        }

        void erreur(int numLigne, String motif) {
            erreurs.add("Ligne " + numLigne + " : " + (motif == null ? "erreur inconnue" : motif));
        }
    }
}
