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
    /** Cellule Excel portant DATE et HEURE : on rend les deux, chaque analyseur prend sa part. */
    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // E5 : plusieurs formats acceptés en entrée (l'établissement produit son propre EDT).
    private static final List<DateTimeFormatter> DATE_FMTS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("d.M.yyyy"),
            DateTimeFormatter.ofPattern("d/M/yy"));
    private static final DateTimeFormatter DATE_EN_TOUTES_LETTRES =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);
    private static final java.util.regex.Pattern DATE_DANS_TEXTE = java.util.regex.Pattern.compile(
            "\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}|\\d{1,2} [\\p{L}]+ \\d{4}");
    private static final java.util.regex.Pattern HEURE_SOUPLE =
            java.util.regex.Pattern.compile("^(\\d{1,2})\\s*[hH:.]?\\s*(\\d{2})?\\s*(?:mn|min)?$");
    private static final java.util.regex.Pattern HORAIRE_EN_DEUX =
            java.util.regex.Pattern.compile("^(.+?)\\s*(?:-|–|—|\\bà\\b|\\ba\\b)\\s*(.+)$");
    private static final Map<String, java.time.DayOfWeek> JOURS = Map.of(
            "lundi", java.time.DayOfWeek.MONDAY, "mardi", java.time.DayOfWeek.TUESDAY,
            "mercredi", java.time.DayOfWeek.WEDNESDAY, "jeudi", java.time.DayOfWeek.THURSDAY,
            "vendredi", java.time.DayOfWeek.FRIDAY, "samedi", java.time.DayOfWeek.SATURDAY,
            "dimanche", java.time.DayOfWeek.SUNDAY);
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
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final PeriodeCalendrierRepository periodeCalendrierRepository;
    private final CompteEnseignantService compteEnseignantService;

    // ─────────────────────────────────────────────────────────────────────────
    // Import de l'emploi du temps
    //
    // Chaque établissement a son propre fichier : on lit donc les TITRES de colonnes, dans
    // n'importe quel ordre et sous leurs différents noms. Les fichiers sans titre reconnu
    // gardent la lecture historique par position :
    //   admin      : DATE | HEURE_DEBUT | HEURE_FIN | MATRICULE_ENSEIGNANT | MATIERE | CLASSE | SALLE
    //   enseignant : DATE | HEURE_DEBUT | HEURE_FIN | MATIERE | CLASSE | SALLE
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    public Map<String, Object> importerPlanning(MultipartFile file) throws Exception {
        return importerPlanning(file, null, null);
    }

    /**
     * @param debut,fin période de validité, pour un fichier qui donne un jour de la semaine
     *                  (« Lundi ») au lieu d'une date : la séance est alors répétée chaque semaine.
     */
    @Transactional
    public Map<String, Object> importerPlanning(MultipartFile file, LocalDate debut, LocalDate fin) throws Exception {
        return importerPlanning(file, debut, fin, null);
    }

    /** @param classe classe d'une grille dont le fichier ne dit pas laquelle elle concerne */
    @Transactional
    public Map<String, Object> importerPlanning(MultipartFile file, LocalDate debut, LocalDate fin, String classe) throws Exception {
        String filename = file.getOriginalFilename();
        ContexteImport ctx = new ContexteImport();  // caches référentiels + erreurs (E3/E4/C6)
        List<Seance> seances = sansDoublons(analyser(lire(file), ctx, null, debut, fin, classe), ctx);
        List<Seance> saved = seanceRepository.saveAll(seances);
        log.info("Import admin : {} séances importées, {} ligne(s) en erreur, {} doublon(s) ignoré(s)",
                saved.size(), ctx.erreurs.size(), ctx.doublons);
        return resultat(filename, saved.size(), ctx, null);
    }

    @Transactional
    public Map<String, Object> importerMonPlanning(MultipartFile file, Long userId) throws Exception {
        Enseignant enseignant = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Profil enseignant introuvable"));

        String filename = file.getOriginalFilename();
        ContexteImport ctx = new ContexteImport();
        List<Seance> seances = sansDoublons(analyser(lire(file), ctx, enseignant, null, null, null), ctx);
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
        result.put("doublonsIgnores", ctx.doublons);
        result.put("joursFermesIgnores", ctx.joursFermes);
        return result;
    }

    // ─── Lecture du fichier ────────────────────────────────────────────────────
    private record Ligne(int numero, String[] cellules) {
        String cellule(int i) {
            return i >= 0 && i < cellules.length && cellules[i] != null ? cellules[i].trim() : "";
        }
        boolean vide() {
            for (String c : cellules) if (c != null && !c.isBlank()) return false;
            return true;
        }
    }

    /** Une feuille du classeur ; les cellules fusionnées disent la durée d'un cours dans une grille. */
    private record Feuille(String nom, List<Ligne> lignes, List<org.apache.poi.ss.util.CellRangeAddress> fusions) {}

    private List<Feuille> lire(MultipartFile file) throws Exception {
        return estCsv(file.getOriginalFilename())
                ? List.of(new Feuille("", lireCsv(file), List.of()))
                : lireClasseur(file);
    }

    /** .xlsx comme .xls : beaucoup de scolarités travaillent encore sur d'anciens fichiers. */
    private List<Feuille> lireClasseur(MultipartFile file) throws Exception {
        List<Feuille> feuilles = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            for (int f = 0; f < workbook.getNumberOfSheets(); f++) {
                if (workbook.isSheetHidden(f)) continue;
                Sheet sheet = workbook.getSheetAt(f);
                List<Ligne> lignes = new ArrayList<>();
                for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    int nb = Math.max(row.getLastCellNum(), 0);
                    String[] cellules = new String[nb];
                    for (int c = 0; c < nb; c++) cellules[c] = getCellValue(row, c);
                    lignes.add(new Ligne(i + 1, cellules));
                }
                feuilles.add(new Feuille(sheet.getSheetName(), lignes, sheet.getMergedRegions()));
            }
        }
        return feuilles;
    }

    private List<Ligne> lireCsv(MultipartFile file) throws Exception {
        String texte = decoder(file.getBytes());
        String[] brutes = texte.split("\r\n|\r|\n", -1);
        char sep = 0;
        List<Ligne> lignes = new ArrayList<>();
        for (int i = 0; i < brutes.length; i++) {
            if (brutes[i].isBlank()) continue;
            if (sep == 0) sep = detecterSeparateur(brutes[i]);
            lignes.add(new Ligne(i + 1, decouperCsv(brutes[i], sep)));
        }
        return lignes;
    }

    /** UTF-8 si le fichier en est, sinon l'encodage d'un CSV enregistré depuis Excel sous Windows. */
    private String decoder(byte[] octets) {
        String texte;
        try {
            texte = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(octets)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            texte = new String(octets, java.nio.charset.Charset.forName("windows-1252"));
        }
        return texte.startsWith("﻿") ? texte.substring(1) : texte;
    }

    /** Respecte les guillemets : « Droit, économie » reste une seule cellule. */
    private String[] decouperCsv(String ligne, char sep) {
        List<String> cellules = new ArrayList<>();
        StringBuilder courante = new StringBuilder();
        boolean entreGuillemets = false;
        for (int i = 0; i < ligne.length(); i++) {
            char c = ligne.charAt(i);
            if (c == '"') {
                if (entreGuillemets && i + 1 < ligne.length() && ligne.charAt(i + 1) == '"') {
                    courante.append('"');
                    i++;
                } else {
                    entreGuillemets = !entreGuillemets;
                }
            } else if (c == sep && !entreGuillemets) {
                cellules.add(courante.toString());
                courante.setLength(0);
            } else {
                courante.append(c);
            }
        }
        cellules.add(courante.toString());
        return cellules.toArray(new String[0]);
    }

    // ─── Repérage des colonnes ─────────────────────────────────────────────────
    private static final Set<String> TITRES_DATE = Set.of(
            "DATE", "JOUR", "DATESEANCE", "DATEDUCOURS", "DATECOURS", "DATEDELASEANCE");
    private static final Set<String> TITRES_DEBUT = Set.of(
            "HEUREDEBUT", "HEUREDEDEBUT", "DEBUT", "HDEBUT", "DE", "DEBUTCOURS", "DEBUTSEANCE", "HEUREDEBUTCOURS");
    private static final Set<String> TITRES_FIN = Set.of(
            "HEUREFIN", "HEUREDEFIN", "FIN", "HFIN", "A", "FINCOURS", "FINSEANCE", "HEUREFINCOURS");
    private static final Set<String> TITRES_HORAIRE = Set.of(
            "HORAIRE", "HORAIRES", "HEURE", "HEURES", "CRENEAU", "CRENEAUX", "PLAGEHORAIRE");
    private static final Set<String> TITRES_ENSEIGNANT = Set.of(
            "MATRICULE", "MATRICULEENSEIGNANT", "PROFESSEURMATRICULE", "ENSEIGNANT", "ENSEIGNANTE", "PROFESSEUR",
            "PROF", "INTERVENANT", "FORMATEUR", "NOMENSEIGNANT", "NOMDELENSEIGNANT", "EMAILENSEIGNANT", "EMAIL",
            "CHARGEDECOURS", "CHARGEDUCOURS");
    private static final Set<String> TITRES_MATIERE = Set.of(
            "MATIERE", "COURS", "UE", "ECUE", "MODULE", "DISCIPLINE", "UNITEENSEIGNEMENT", "UNITEDENSEIGNEMENT",
            "INTITULE", "INTITULEDUCOURS", "ENSEIGNEMENT", "LIBELLE");
    private static final Set<String> TITRES_CLASSE = Set.of(
            "CLASSE", "NIVEAU", "NIVEAUGROUPE", "CLASSENIVEAU", "FILIERE", "GROUPE", "PROMOTION", "PROMO", "SECTION",
            "PARCOURS");
    private static final Set<String> TITRES_SALLE = Set.of(
            "SALLE", "LOCAL", "LIEU", "AMPHI", "AMPHITHEATRE", "AMPHITHEATRESALLE", "SALLEDECOURS");

    /** Où se trouve chaque information dans le fichier. -1 : absente. */
    private record PlanColonnes(int ligneEntete, int date, int debut, int fin, int horaire,
                                int enseignant, int matiere, int classe, int salle) {}

    private static String titre(String s) {
        if (s == null) return "";
        String sansAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sansAccents.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
    }

    private static int chercher(String[] titres, Set<String> attendus) {
        for (int i = 0; i < titres.length; i++) if (attendus.contains(titres[i])) return i;
        return -1;
    }

    /**
     * La ligne de titres n'est pas toujours la première : un emploi du temps commence souvent par
     * le nom de l'établissement ou du semestre. On la cherche donc dans les premières lignes.
     */
    private PlanColonnes reperer(List<Ligne> lignes, boolean enseignantRequis) {
        PlanColonnes partiel = null;
        int meilleur = 0;
        for (int l = 0; l < Math.min(15, lignes.size()); l++) {
            String[] titres = Arrays.stream(lignes.get(l).cellules()).map(ImportService::titre).toArray(String[]::new);
            PlanColonnes p = new PlanColonnes(l, chercher(titres, TITRES_DATE), chercher(titres, TITRES_DEBUT),
                    chercher(titres, TITRES_FIN), chercher(titres, TITRES_HORAIRE),
                    chercher(titres, TITRES_ENSEIGNANT), chercher(titres, TITRES_MATIERE),
                    chercher(titres, TITRES_CLASSE), chercher(titres, TITRES_SALLE));
            int score = 0;
            for (int v : new int[] {p.date(), p.debut(), p.fin(), p.horaire(), p.enseignant(), p.matiere(), p.classe(), p.salle()}) {
                if (v >= 0) score++;
            }
            if (score > meilleur) {
                meilleur = score;
                partiel = p;
            }
        }
        // Aucun titre parlant : l'ordre historique reste valable.
        if (partiel == null || meilleur < 3) {
            return enseignantRequis
                    ? new PlanColonnes(0, 0, 1, 2, -1, 3, 4, 5, 6)
                    : new PlanColonnes(0, 0, 1, 2, -1, -1, 3, 4, 5);
        }
        List<String> manquants = new ArrayList<>();
        if (partiel.date() < 0) manquants.add("date ou jour");
        if (partiel.horaire() < 0 && (partiel.debut() < 0 || partiel.fin() < 0)) manquants.add("heure de début et de fin (ou horaire)");
        if (enseignantRequis && partiel.enseignant() < 0) manquants.add("enseignant");
        if (partiel.matiere() < 0) manquants.add("matière");
        if (partiel.classe() < 0) manquants.add("classe");
        if (partiel.salle() < 0) manquants.add("salle");
        if (!manquants.isEmpty()) {
            throw new IllegalArgumentException("colonne introuvable dans le fichier : " + String.join(", ", manquants)
                    + ". Vérifiez le titre de ces colonnes.");
        }
        return partiel;
    }

    // ─── Construction des séances ──────────────────────────────────────────────
    private List<Seance> analyser(List<Feuille> feuilles, ContexteImport ctx, Enseignant enseignantFixe,
                                  LocalDate debut, LocalDate fin, String classe) {
        List<Seance> seances = new ArrayList<>();
        boolean grille = false;
        // Une grille par feuille est l'usage courant : une feuille par classe.
        for (Feuille feuille : feuilles) {
            Grille g = reconnaitreGrille(feuille);
            if (g == null) g = reconnaitreGrille(transposer(feuille));
            if (g == null) continue;
            grille = true;
            seances.addAll(analyserGrille(g, ctx, enseignantFixe, debut, fin, classe));
        }
        if (grille || feuilles.isEmpty()) return seances;
        return analyserListe(feuilles.get(0).lignes(), ctx, enseignantFixe, debut, fin);
    }

    private List<Seance> analyserListe(List<Ligne> lignes, ContexteImport ctx, Enseignant enseignantFixe,
                                       LocalDate debut, LocalDate fin) {
        List<Seance> seances = new ArrayList<>();
        if (lignes.isEmpty()) return seances;
        PlanColonnes plan = reperer(lignes, enseignantFixe == null);
        // Une date en cellule fusionnée ne figure que sur la première ligne de son groupe.
        String dateEnCours = null;
        for (int i = plan.ligneEntete() + 1; i < lignes.size(); i++) {
            Ligne ligne = lignes.get(i);
            if (ligne.vide()) continue;
            try {
                String date = ligne.cellule(plan.date());
                if (date.isBlank()) date = dateEnCours;
                else dateEnCours = date;
                if (date == null) throw new IllegalArgumentException("date absente");
                seances.addAll(construireSeances(ligne, plan, date, ctx, enseignantFixe, debut, fin));
            } catch (Exception e) {
                ctx.erreur(ligne.numero(), e.getMessage());
            }
        }
        return seances;
    }

    private List<Seance> construireSeances(Ligne ligne, PlanColonnes plan, String date, ContexteImport ctx,
                                           Enseignant enseignantFixe, LocalDate debut, LocalDate fin) {
        LocalTime heureDebut;
        LocalTime heureFin;
        if (plan.debut() >= 0 && plan.fin() >= 0) {
            heureDebut = parseTime(ligne.cellule(plan.debut()));
            heureFin = parseTime(ligne.cellule(plan.fin()));
        } else {
            String horaire = ligne.cellule(plan.horaire());
            java.util.regex.Matcher m = HORAIRE_EN_DEUX.matcher(horaire);
            if (!m.matches()) throw new IllegalArgumentException("horaire invalide « " + horaire + " » (attendu 08:00 - 10:00)");
            heureDebut = parseTime(m.group(1));
            heureFin = parseTime(m.group(2));
        }
        Enseignant enseignant = enseignantFixe != null ? enseignantFixe
                : ctx.trouverEnseignant(exigerLibelle(ligne.cellule(plan.enseignant()), "enseignant"));
        Matiere matiere = ctx.upsertMatiere(exigerLibelle(ligne.cellule(plan.matiere()), "matière"));
        Classe classe = ctx.upsertClasse(exigerLibelle(ligne.cellule(plan.classe()), "classe"));
        Salle salle = ctx.upsertSalle(exigerLibelle(ligne.cellule(plan.salle()), "salle"));

        List<Seance> seances = new ArrayList<>();
        for (LocalDate jour : dates(date, debut, fin, ctx)) {
            seances.add(seance(jour, heureDebut, heureFin, enseignant, matiere, classe, salle, enseignantFixe));
        }
        return seances;
    }

    private Seance seance(LocalDate jour, LocalTime heureDebut, LocalTime heureFin, Enseignant enseignant,
                          Matiere matiere, Classe classe, Salle salle, Enseignant enseignantFixe) {
        return Seance.builder()
                .date(jour)
                .heureDebut(heureDebut)
                .heureFin(heureFin)
                .enseignant(enseignant)
                .matiere(matiere)
                .classe(classe)
                .salle(salle)
                .type(TypeSeance.NORMALE)
                // Une séance émargée devient des heures payées : celle qu'un enseignant déclare
                // lui-même attend l'accord de la scolarité. Seul l'import admin passe directement.
                .statut(enseignantFixe == null ? StatutSeance.A_FAIRE : StatutSeance.A_VALIDER)
                .build();
    }

    // ─── Emploi du temps en grille ─────────────────────────────────────────────
    // Les jours en colonnes, les créneaux en lignes (ou l'inverse) ; chaque case décrit un cours.
    private record Creneau(LocalTime debut, LocalTime fin) {}
    private record Grille(Feuille feuille, int ligneJours, int colHoraire, int colFin, Map<Integer, String> jours) {}
    private record EnteteGrille(String classe, String salle, LocalDate debut, LocalDate fin) {}

    private static final java.util.regex.Pattern ENTETE_CLASSE = java.util.regex.Pattern.compile(
            "(?iu)^\\s*(?:classe|niveau|fili[eè]re|groupe|promotion|promo|parcours|section)\\s*[:–—-]\\s*(.+)$");
    private static final java.util.regex.Pattern ENTETE_SALLE = java.util.regex.Pattern.compile(
            "(?iu)^\\s*(?:salle|local)\\s*[:–—-]\\s*(.+)$");
    private static final java.util.regex.Pattern ENTETE_TITRE = java.util.regex.Pattern.compile(
            "(?iu)emploi du temps\\s*[:–—-]\\s*(.+)$");
    private static final java.util.regex.Pattern CIVILITE = java.util.regex.Pattern.compile(
            "(?iu)^(?:m\\.|mr\\.?|mme\\.?|mlle\\.?|dr\\.?|pr\\.?|prof\\.?|professeur|enseignant|ens\\.?)\\s*:?\\s*");
    private static final java.util.regex.Pattern SALLE_NOMMEE = java.util.regex.Pattern.compile(
            "(?iu)^(?:salle|amphi|amphith[eé][aâ]tre|labo|laboratoire|local|atelier)\\b.*");
    private static final java.util.regex.Pattern SALLE_CODE = java.util.regex.Pattern.compile("^[A-Za-z]{1,3}[- ]?\\d{2,4}$");
    private static final java.util.regex.Pattern FEUILLE_SANS_CLASSE = java.util.regex.Pattern.compile(
            "(?iu)^(?:feuil|feuille|sheet|edt|emploi|planning|semaine|calendrier).*");
    private static final Set<String> CASES_SANS_COURS = Set.of("pause", "recreation", "recre", "dejeuner",
            "libre", "ferie", "conge", "vacances", "x", "-", "--", "/");
    private static final Set<String> TYPES_DE_COURS = Set.of("cm", "td", "tp", "cours", "ed", "tpe");
    private static final Map<String, java.time.DayOfWeek> JOURS_COURTS = Map.of(
            "lun", java.time.DayOfWeek.MONDAY, "mar", java.time.DayOfWeek.TUESDAY,
            "mer", java.time.DayOfWeek.WEDNESDAY, "jeu", java.time.DayOfWeek.THURSDAY,
            "ven", java.time.DayOfWeek.FRIDAY, "sam", java.time.DayOfWeek.SATURDAY,
            "dim", java.time.DayOfWeek.SUNDAY);

    private java.time.DayOfWeek jourDe(String valeur) {
        String[] mots = cle(valeur).split("[^a-z]+");
        if (mots.length == 0) return null;
        java.time.DayOfWeek jour = JOURS.get(mots[0]);
        if (jour != null) return jour;
        return mots[0].equals(cle(valeur).replace(".", "")) ? JOURS_COURTS.get(mots[0]) : null;
    }

    private Creneau creneau(String texte) {
        if (texte == null || texte.isBlank()) return null;
        java.util.regex.Matcher m = HORAIRE_EN_DEUX.matcher(texte.trim());
        if (!m.matches()) return null;
        LocalTime debut = essayerHeure(m.group(1));
        LocalTime fin = essayerHeure(m.group(2));
        return debut != null && fin != null && fin.isAfter(debut) ? new Creneau(debut, fin) : null;
    }

    private Creneau creneau(Grille g, Ligne ligne) {
        if (g.colFin() < 0) return creneau(ligne.cellule(g.colHoraire()));
        LocalTime debut = essayerHeure(ligne.cellule(g.colHoraire()));
        LocalTime fin = essayerHeure(ligne.cellule(g.colFin()));
        return debut != null && fin != null && fin.isAfter(debut) ? new Creneau(debut, fin) : null;
    }

    /** Une ligne d'au moins deux jours, précédée d'une colonne qui donne bien des créneaux. */
    private Grille reconnaitreGrille(Feuille feuille) {
        List<Ligne> lignes = feuille.lignes();
        for (int l = 0; l < Math.min(15, lignes.size()); l++) {
            String[] cellules = lignes.get(l).cellules();
            Map<Integer, String> jours = new TreeMap<>();
            for (int c = 0; c < cellules.length; c++) {
                if (cellules[c] == null || cellules[c].isBlank()) continue;
                if (jourDe(cellules[c]) != null || essayerDate(cellules[c].trim()) != null) jours.put(c, cellules[c].trim());
            }
            if (jours.size() < 2) continue;
            int premiere = jours.keySet().iterator().next();
            for (int col = 0; col < premiere; col++) {
                Grille enUne = new Grille(feuille, l, col, -1, jours);
                if (donneDesCreneaux(enUne)) return enUne;
                if (col + 1 < premiere) {
                    Grille enDeux = new Grille(feuille, l, col, col + 1, jours);
                    if (donneDesCreneaux(enDeux)) return enDeux;
                }
            }
        }
        return null;
    }

    private boolean donneDesCreneaux(Grille g) {
        List<Ligne> lignes = g.feuille().lignes();
        for (int i = g.ligneJours() + 1; i < lignes.size(); i++) {
            if (creneau(g, lignes.get(i)) != null) return true;
        }
        return false;
    }

    /** Les jours en lignes et les créneaux en colonnes : on se ramène au cas courant. */
    private Feuille transposer(Feuille feuille) {
        int nbLignes = 0;
        int nbColonnes = 0;
        for (Ligne l : feuille.lignes()) {
            nbLignes = Math.max(nbLignes, l.numero());
            nbColonnes = Math.max(nbColonnes, l.cellules().length);
        }
        String[][] cases = new String[nbColonnes][nbLignes];
        for (Ligne l : feuille.lignes()) {
            for (int c = 0; c < l.cellules().length; c++) cases[c][l.numero() - 1] = l.cellules()[c];
        }
        List<Ligne> lignes = new ArrayList<>();
        for (int c = 0; c < nbColonnes; c++) lignes.add(new Ligne(c + 1, cases[c]));
        List<org.apache.poi.ss.util.CellRangeAddress> fusions = feuille.fusions().stream()
                .map(z -> new org.apache.poi.ss.util.CellRangeAddress(
                        z.getFirstColumn(), z.getLastColumn(), z.getFirstRow(), z.getLastRow()))
                .toList();
        return new Feuille(feuille.nom(), lignes, fusions);
    }

    private List<Seance> analyserGrille(Grille g, ContexteImport ctx, Enseignant enseignantFixe,
                                        LocalDate debut, LocalDate fin, String classeFormulaire) {
        EnteteGrille entete = lireEntete(g);
        String classe = entete.classe() != null ? entete.classe()
                : !isBlank(classeFormulaire) ? classeFormulaire.trim()
                : !g.feuille().nom().isBlank() && !FEUILLE_SANS_CLASSE.matcher(g.feuille().nom().trim()).matches()
                        ? g.feuille().nom().trim() : null;
        LocalDate du = debut != null ? debut : entete.debut();
        LocalDate au = fin != null ? fin : entete.fin();

        List<Seance> seances = new ArrayList<>();
        List<Ligne> lignes = g.feuille().lignes();
        for (int i = g.ligneJours() + 1; i < lignes.size(); i++) {
            Ligne ligne = lignes.get(i);
            Creneau creneau = creneau(g, ligne);
            if (creneau == null) continue;
            int rang = ligne.numero() - 1;
            for (Map.Entry<Integer, String> jour : g.jours().entrySet()) {
                int col = jour.getKey();
                org.apache.poi.ss.util.CellRangeAddress zone = fusion(g.feuille(), rang, col);
                if (zone != null && zone.getFirstRow() < rang) continue;   // suite d'un cours déjà lu
                String texte = ligne.cellule(col);
                if (texte.isBlank() && zone != null && zone.getFirstColumn() < col) texte = ligne.cellule(zone.getFirstColumn());
                if (texte.isBlank() || CASES_SANS_COURS.contains(cle(texte))) continue;
                LocalTime heureFin = zone == null ? creneau.fin() : finDeZone(g, i, zone, creneau.fin());
                try {
                    seances.addAll(seancesDeLaCase(texte, jour.getValue(), creneau.debut(), heureFin,
                            classe, entete.salle(), ctx, enseignantFixe, du, au));
                } catch (IllegalArgumentException e) {
                    String feuille = g.feuille().nom().isBlank() ? "" : g.feuille().nom() + ", ";
                    ctx.erreur(ligne.numero(), feuille + jour.getValue() + " " + creneau.debut() + " : " + e.getMessage());
                }
            }
        }
        return seances;
    }

    private org.apache.poi.ss.util.CellRangeAddress fusion(Feuille feuille, int rang, int col) {
        for (org.apache.poi.ss.util.CellRangeAddress z : feuille.fusions()) {
            if (z.isInRange(rang, col)) return z;
        }
        return null;
    }

    /** Un cours fusionné sur plusieurs créneaux finit avec le dernier d'entre eux. */
    private LocalTime finDeZone(Grille g, int indice, org.apache.poi.ss.util.CellRangeAddress zone, LocalTime fin) {
        LocalTime resultat = fin;
        List<Ligne> lignes = g.feuille().lignes();
        for (int i = indice + 1; i < lignes.size() && lignes.get(i).numero() - 1 <= zone.getLastRow(); i++) {
            Creneau suivant = creneau(g, lignes.get(i));
            if (suivant != null) resultat = suivant.fin();
        }
        return resultat;
    }

    /** Au-dessus des jours : la classe, parfois la salle, parfois la semaine concernée. */
    private EnteteGrille lireEntete(Grille g) {
        String classe = null;
        String salle = null;
        LocalDate debut = null;
        LocalDate fin = null;
        for (int l = 0; l < g.ligneJours(); l++) {
            for (String cellule : g.feuille().lignes().get(l).cellules()) {
                if (cellule == null || cellule.isBlank()) continue;
                java.util.regex.Matcher m = ENTETE_CLASSE.matcher(cellule);
                if (m.matches()) classe = m.group(1).trim();
                m = ENTETE_SALLE.matcher(cellule);
                if (m.matches()) salle = m.group(1).trim();
                m = ENTETE_TITRE.matcher(cellule);
                if (m.find() && classe == null) {
                    String titre = m.group(1).split("\\s[-–—]\\s")[0].trim();
                    if (!cle(titre).matches("^(semaine|semestre|annee|periode|du )(.*)")) classe = titre;
                }
                List<LocalDate> trouvees = new ArrayList<>();
                java.util.regex.Matcher d = DATE_DANS_TEXTE.matcher(cellule);
                while (d.find()) {
                    LocalDate date = essayerDate(d.group());
                    if (date != null) trouvees.add(date);
                }
                if (trouvees.size() >= 2) {
                    debut = trouvees.get(0);
                    fin = trouvees.get(1);
                } else if (trouvees.size() == 1 && cle(cellule).contains("semaine")) {
                    debut = trouvees.get(0);
                    fin = debut.plusDays(6);
                }
            }
        }
        return new EnteteGrille(classe, salle, debut, fin);
    }

    /** « Réseaux - M. Kouassi - B12 », sur une ou plusieurs lignes, dans n'importe quel ordre. */
    private List<Seance> seancesDeLaCase(String texte, String jour, LocalTime heureDebut, LocalTime heureFin,
                                         String classeGrille, String salleGrille, ContexteImport ctx,
                                         Enseignant enseignantFixe, LocalDate du, LocalDate au) {
        Enseignant enseignant = enseignantFixe;
        String classe = classeGrille;
        String salle = salleGrille;
        List<String> reste = new ArrayList<>();
        for (String partie : texte.replaceAll("[()\\[\\]]", "\n").split("\\R|\\s+[-–—/|]\\s+|\\s*;\\s*|\\s*\\|\\s*")) {
            String v = partie.trim();
            if (v.isEmpty()) continue;
            Enseignant trouve = ctx.chercherEnseignant(CIVILITE.matcher(v).replaceFirst("").trim());
            if (trouve != null) {
                if (enseignantFixe == null) enseignant = trouve;
                continue;
            }
            if (ctx.classes.containsKey(cle(v))) {
                classe = v;
            } else if (ctx.salles.containsKey(cle(v)) || SALLE_NOMMEE.matcher(v).matches() || SALLE_CODE.matcher(v).matches()) {
                salle = v.replaceFirst("(?iu)^(?:salle|local)\\s*:?\\s*", "");
            } else if (!TYPES_DE_COURS.contains(cle(v))) {
                reste.add(v);
            }
        }
        if (reste.isEmpty()) throw new IllegalArgumentException("matière introuvable dans « " + une(texte) + " »");
        if (enseignant == null) {
            throw new IllegalArgumentException("aucun enseignant de l'annuaire reconnu dans « " + une(texte)
                    + " » (matricule, e-mail ou nom)");
        }
        if (isBlank(salle)) throw new IllegalArgumentException("aucune salle dans « " + une(texte) + " »");
        if (isBlank(classe)) {
            throw new IllegalArgumentException("classe inconnue : indiquez-la à l'import, ou dans une cellule « Classe : L3 » au-dessus de la grille");
        }
        Matiere matiere = ctx.upsertMatiere(reste.get(0));
        Classe laClasse = ctx.upsertClasse(classe);
        Salle laSalle = ctx.upsertSalle(salle);
        List<Seance> seances = new ArrayList<>();
        for (LocalDate date : dates(jour, du, au, ctx)) {
            seances.add(seance(date, heureDebut, heureFin, enseignant, matiere, laClasse, laSalle, enseignantFixe));
        }
        return seances;
    }

    private static String une(String texte) {
        return texte.replaceAll("\\s*\\R\\s*", " / ");
    }

    /** Une date précise, ou un jour de la semaine répété sur la période indiquée, hors fermetures. */
    private List<LocalDate> dates(String valeur, LocalDate debut, LocalDate fin, ContexteImport ctx) {
        java.time.DayOfWeek jour = valeur.matches(".*\\d.*") ? null : jourDe(valeur);
        if (jour == null) return List.of(parseDate(valeur));
        if (debut == null || fin == null || fin.isBefore(debut)) {
            throw new IllegalArgumentException("« " + valeur.trim() + " » n'est pas une date : "
                    + "indiquez la période de validité de cet emploi du temps à l'import");
        }
        List<LocalDate> dates = new ArrayList<>();
        LocalDate d = debut.with(java.time.temporal.TemporalAdjusters.nextOrSame(jour));
        for (; !d.isAfter(fin); d = d.plusWeeks(1)) {
            if (ctx.estFerme(d)) ctx.joursFermes++;
            else dates.add(d);
        }
        return dates;
    }

    /** Réimporter un fichier corrigé ne doit pas dédoubler les séances, donc les heures à payer. */
    private List<Seance> sansDoublons(List<Seance> seances, ContexteImport ctx) {
        if (seances.isEmpty()) return seances;
        LocalDate min = seances.stream().map(Seance::getDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate max = seances.stream().map(Seance::getDate).max(LocalDate::compareTo).orElseThrow();
        Set<String> connues = new HashSet<>();
        for (Seance s : seanceRepository.findByDateBetweenOrderByDateAscHeureDebutAsc(min, max)) connues.add(empreinte(s));
        List<Seance> nouvelles = new ArrayList<>();
        for (Seance s : seances) {
            if (connues.add(empreinte(s))) nouvelles.add(s);
            else ctx.doublons++;
        }
        return nouvelles;
    }

    private static String empreinte(Seance s) {
        return s.getDate() + "|" + s.getHeureDebut() + "|"
                + (s.getEnseignant() == null ? "" : s.getEnseignant().getId()) + "|"
                + (s.getClasse() == null ? "" : cle(s.getClasse().getLibelle()));
    }


    // ─── Import d'annuaire enseignants (inchangé sur le fond) ──────────────────
    // EMAIL est FACULTATIVE et volontairement en dernier : les fichiers déjà en circulation,
    // qui s'arrêtent à GRADE, restent acceptés tels quels. Renseignée, elle déclenche la création
    // du compte et l'envoi des accès — sans elle, l'import ne produit qu'un annuaire, et
    // l'enseignant n'aura aucun moyen de se connecter (l'auto-inscription n'existe plus).
    private static final String[] COLONNES_ENSEIGNANTS =
            {"MATRICULE", "NOM", "PRENOM", "DEPARTEMENT", "GRADE", "EMAIL"};

    @Transactional
    public Map<String, Object> importerEnseignants(MultipartFile file) throws Exception {
        int importes = 0, ignores = 0, comptesCrees = 0, sansEmail = 0;
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
                // Compte + accès envoyés par courriel dès qu'une adresse est fournie.
                String email = emptyToNull(getCellValue(row, 5));
                User compte = null;
                String secretProvisoire = null;
                boolean rattachement = false;
                boolean adresseRefusee = false;
                if (email != null) {
                    try {
                        // Vacataire déjà inscrit ailleurs : même compte, aucun nouveau secret.
                        compte = compteEnseignantService.compteARattacher(email, tenantId).orElse(null);
                        rattachement = compte != null;
                    } catch (IllegalArgumentException refus) {
                        adresseRefusee = true;
                    }
                }
                if (email == null || adresseRefusee) {
                    sansEmail++;
                } else if (!rattachement) {
                    secretProvisoire = ci.esatic.sigep.security.SecurityUtils.genererMotDePasseProvisoire();
                    compte = User.builder()
                            .email(email)
                            .password(passwordEncoder.encode(secretProvisoire))
                            .mustChangePassword(true)
                            .roles(Set.of(roleRepository.findByName(ERole.ROLE_ENSEIGNANT).orElseThrow()))
                            // Sans rattachement au tenant, la connexion mobile ne poserait aucun
                            // établissement et désactiverait le cloisonnement des lectures.
                            .etablissement(tenant)
                            .build();
                    userRepository.save(compte);
                    comptesCrees++;
                }

                Enseignant e = Enseignant.builder()
                        .matricule(matricule)
                        .nom(nom)
                        .prenom(prenom)
                        .departement(emptyToNull(getCellValue(row, 3)))
                        .grade(emptyToNull(getCellValue(row, 4)))
                        // Sans compte, le statut reste en attente : rien à valider tant que
                        // l'enseignant ne peut pas se connecter.
                        .statut(compte == null ? StatutEnseignant.PENDING : StatutEnseignant.VALIDATED)
                        .user(compte)
                        .build();
                enseignantRepository.save(e);
                importes++;

                if (rattachement) {
                    mailService.notifierRattachementEtablissement(tenant == null ? null : tenant.getEmailFrom(),
                            email, prenom, tenant == null ? "Un établissement" : tenant.getNomEffectif());
                } else if (compte != null) {
                    // Après la sauvegarde : inutile d'envoyer des accès si la ligne échoue ensuite.
                    mailService.notifierIdentifiantsProvisoires(
                            tenant == null ? null : tenant.getEmailFrom(), email, prenom, secretProvisoire);
                }
            }
        }
        log.info("Import enseignants : {} créés ({} avec compte, {} sans e-mail exploitable), "
                        + "{} ignorés (matricule existant), {} ligne(s) invalide(s) {}, quotaAtteint={}",
                importes, comptesCrees, sansEmail, ignores, lignesInvalides.size(), lignesInvalides, quotaAtteint);
        Map<String, Object> result = new HashMap<>();
        result.put("importes", importes);
        result.put("ignores", ignores);
        result.put("lignesInvalides", lignesInvalides);
        result.put("quotaAtteint", quotaAtteint);
        result.put("comptesCrees", comptesCrees);
        result.put("sansEmail", sansEmail);
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

    private LocalDate parseDate(String s) {
        String v = s.trim();
        // Une cellule « date + heure » arrive sous la forme « jj/mm/aaaa HH:mm » : seule la
        // partie date nous concerne ici, et la refuser perdrait une ligne parfaitement valable.
        int espace = v.indexOf(' ');
        if (espace > 0) {
            LocalDate partie = essayerDate(v.substring(0, espace));
            if (partie != null) return partie;
        }
        LocalDate d = essayerDate(v);
        if (d != null) return d;
        // « Lundi 03/11/2026 », « Séance du 3 novembre 2026 » : la date est dans le texte.
        java.util.regex.Matcher dansTexte = DATE_DANS_TEXTE.matcher(v);
        if (dansTexte.find()) {
            d = essayerDate(dansTexte.group());
            if (d != null) return d;
        }
        throw new IllegalArgumentException("date invalide « " + v + " » (attendu jj/mm/aaaa)");
    }

    private LocalDate essayerDate(String v) {
        for (DateTimeFormatter f : DATE_FMTS) {
            try { return LocalDate.parse(v, f); } catch (Exception ignore) { /* format suivant */ }
        }
        try { return LocalDate.parse(v.toLowerCase(Locale.FRENCH), DATE_EN_TOUTES_LETTRES); }
        catch (Exception ignore) { /* pas en toutes lettres */ }
        return null;
    }

    private LocalTime parseTime(String s) {
        String brut = s.trim();
        // Symétrique de parseDate : d'une cellule « date + heure », c'est l'heure qu'on veut.
        int espace = brut.lastIndexOf(' ');
        if (espace > 0) {
            LocalTime partie = essayerHeure(brut.substring(espace + 1));
            if (partie != null) return partie;
        }
        LocalTime h = essayerHeure(brut.replace(" ", ""));
        if (h != null) return h;
        throw new IllegalArgumentException("heure invalide « " + brut + " » (attendu HH:mm)");
    }

    private LocalTime essayerHeure(String v) {
        String x = v.trim().replace(" ", "");
        for (DateTimeFormatter f : TIME_FMTS) {
            try { return LocalTime.parse(x, f); } catch (Exception ignore) { /* format suivant */ }
        }
        // « 8h », « 8h30 », « 8.30 », « 08H00 » : les écritures qu'on croise dans les fichiers.
        java.util.regex.Matcher m = HEURE_SOUPLE.matcher(x);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int mn = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if (h < 24 && mn < 60) return LocalTime.of(h, mn);
        }
        return null;
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
                    java.time.LocalDateTime dt = cell.getLocalDateTimeCellValue();
                    // Excel n'a pas de type « heure » : une heure seule est une FRACTION de
                    // journée posée sur son époque, le 31/12/1899. Prendre la partie date d'une
                    // telle cellule rendait « 31/12/1899 », que parseTime refusait — et la ligne
                    // entière était alors écartée en silence. C'est ainsi qu'un emploi du temps
                    // arrivait troué, seules passant les lignes dont les heures étaient du texte.
                    if (dt.getYear() <= 1900) {
                        yield dt.toLocalTime().format(TIME_FMT);
                    }
                    // Date à minuit : le cas courant de la colonne DATE.
                    if (dt.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) {
                        yield dt.toLocalDate().format(DATE_FMT);
                    }
                    // Date ET heure : on rend les deux plutôt que d'en sacrifier une.
                    yield dt.format(DATE_TIME_FMT);
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
        int doublons = 0;
        int joursFermes = 0;
        private List<PeriodeCalendrier> fermetures;
        private Map<String, Enseignant> annuaire;
        private final Set<String> homonymes = new HashSet<>();

        /** Par matricule, adresse e-mail ou nom et prénom : les fichiers n'ont pas tous de matricule. */
        Enseignant trouverEnseignant(String valeur) {
            String v = valeur.trim();
            Optional<Enseignant> parMatricule = enseignantRepository.findByMatricule(v);
            if (parMatricule.isPresent()) return parMatricule.get();
            if (annuaire == null) chargerAnnuaire();
            String cle = cle(v);
            if (homonymes.contains(cle)) {
                throw new IllegalArgumentException("plusieurs enseignants s'appellent « " + v + " » : indiquez le matricule");
            }
            Enseignant e = annuaire.get(cle);
            if (e == null) {
                throw new IllegalArgumentException("enseignant inconnu « " + v
                        + " » : ni matricule, ni e-mail, ni nom de l'annuaire (importez d'abord l'annuaire)");
            }
            return e;
        }

        /** Comme {@link #trouverEnseignant}, sans refus : une case de grille mêle plusieurs informations. */
        Enseignant chercherEnseignant(String valeur) {
            String v = valeur.trim();
            if (v.isEmpty()) return null;
            Optional<Enseignant> parMatricule = enseignantRepository.findByMatricule(v);
            if (parMatricule.isPresent()) return parMatricule.get();
            if (annuaire == null) chargerAnnuaire();
            String c = cle(v);
            return homonymes.contains(c) ? null : annuaire.get(c);
        }

        boolean estFerme(LocalDate jour) {
            if (fermetures == null) {
                fermetures = periodeCalendrierRepository.findAllByOrderByDebutAsc().stream()
                        .filter(p -> p.getType().ferme()).toList();
            }
            return fermetures.stream().anyMatch(p -> p.contient(jour));
        }

        private void chargerAnnuaire() {
            annuaire = new HashMap<>();
            for (Enseignant e : enseignantRepository.findAll()) {
                ajouter(cle(e.getPrenom() + " " + e.getNom()), e);
                ajouter(cle(e.getNom() + " " + e.getPrenom()), e);
                ajouter(cle(e.getNom()), e);
                if (e.getUser() != null && e.getUser().getEmail() != null) ajouter(cle(e.getUser().getEmail()), e);
            }
        }

        private void ajouter(String cle, Enseignant e) {
            Enseignant deja = annuaire.putIfAbsent(cle, e);
            if (deja != null && !Objects.equals(deja.getId(), e.getId())) homonymes.add(cle);
        }

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
