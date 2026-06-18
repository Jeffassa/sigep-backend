package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import ci.esatic.sigep.service.RapportService;
import ci.esatic.sigep.service.EnseignantService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequiredArgsConstructor
public class AdminWebController {

    private final EnseignantRepository enseignantRepository;
    private final SeanceRepository seanceRepository;
    private final EmargementRepository emargementRepository;
    private final DemandeRattrapageRepository rattrapageRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RapportService rapportService;
    private final EnseignantService enseignantService;
    private final FaculteRepository faculteRepository;
    private final MatiereRepository matiereRepository;
    private final ClasseRepository classeRepository;
    private final SalleRepository salleRepository;

    // ─── COMMUN ───────────────────────────────────────────────────────────────

    /** Compteur d'alertes exposé à toutes les vues admin (badge de la barre supérieure). */
    @ModelAttribute("alertesCount")
    public long alertesCount() {
        return compterAlertes();
    }

    private long compterAlertes() {
        return seanceRepository.countSeancesNonEmargees(LocalDate.now())
                + rattrapageRepository.countByStatut(StatutDemande.EN_ATTENTE)
                + enseignantRepository.countByStatut(StatutEnseignant.PENDING);
    }

    // ─── LOGIN ───────────────────────────────────────────────────────────────

    @GetMapping("/admin-login")
    public String loginPage(@RequestParam(required = false) String error,
                            @RequestParam(required = false) String logout,
                            Model model) {
        if (error != null) model.addAttribute("error", "Identifiants incorrects.");
        if (logout != null) model.addAttribute("logout", "Deconnexion reussie.");
        return "admin/login";
    }

    // ─── RACINE ───────────────────────────────────────────────────────────────

    @GetMapping({"/admin", "/admin/"})
    public String adminRoot() {
        return "redirect:/admin/dashboard";
    }

    // ─── DASHBOARD ───────────────────────────────────────────────────────────

    @GetMapping("/admin/dashboard")
    public String dashboard(Model model) {
        model.addAllAttributes(computeDashboardStats());
        // Flux d'activités récentes : dernières demandes de rattrapage
        model.addAttribute("activitesRecentes",
                rattrapageRepository.findAllByOrderByDateCreationDesc()
                        .stream().limit(6).toList());
        return "admin/dashboard";
    }

    /** Données du dashboard en JSON, consommées par l'auto-actualisation côté client. */
    @GetMapping("/admin/api/stats")
    @ResponseBody
    public Map<String, Object> dashboardStats() {
        return computeDashboardStats();
    }

    private Map<String, Object> computeDashboardStats() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);

        long seancesSemaine = seanceRepository.countAllByDateBetween(monday, monday.plusDays(6));
        long emargesSemaine = seanceRepository.countAllEmargesByDateBetween(monday, monday.plusDays(6));
        double tauxEmargement = seancesSemaine > 0
                ? Math.round(emargesSemaine * 1000.0 / seancesSemaine) / 10.0 : 0.0;

        double[] tauxParJour = new double[6];
        for (int i = 0; i < 6; i++) {
            LocalDate day = monday.plusDays(i);
            long s = seanceRepository.countAllByDate(day);
            long e = seanceRepository.countAllEmargesByDate(day);
            tauxParJour[i] = s > 0 ? Math.round(e * 1000.0 / s) / 10.0 : 0.0;
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalEnseignants", enseignantRepository.count());
        stats.put("totalSeancesAujourdhui", seanceRepository.countAllByDate(today));
        stats.put("rattrapagesEnAttente", rattrapageRepository.countByStatut(StatutDemande.EN_ATTENTE));
        stats.put("tauxEmargement", tauxEmargement);
        stats.put("heuresEffectuees", emargesSemaine * 2);
        stats.put("tauxParJour", tauxParJour);
        stats.put("alertesCount", compterAlertes());
        return stats;
    }

    // ─── ALERTES ──────────────────────────────────────────────────────────────

    @GetMapping("/admin/alertes")
    public String alertes(Model model) {
        model.addAttribute("seancesNonEmargees", seanceRepository.findSeancesNonEmargees(LocalDate.now()));
        model.addAttribute("rattrapagesEnAttente",
                rattrapageRepository.findByStatutOrderByDateCreationDesc(StatutDemande.EN_ATTENTE));
        model.addAttribute("enseignantsEnAttente",
                enseignantRepository.findByStatutOrderByNomAsc(StatutEnseignant.PENDING));
        return "admin/alertes";
    }

    // ─── REFERENTIELS (matieres / classes / salles) ────────────────────────────

    @GetMapping("/admin/referentiels")
    public String referentiels(Model model) {
        model.addAttribute("matieres", matiereRepository.findAll());
        model.addAttribute("classes", classeRepository.findAll());
        model.addAttribute("salles", salleRepository.findAll());
        return "admin/referentiels";
    }

    // --- Matières ---
    @PostMapping("/admin/matieres")
    public String creerMatiere(@RequestParam String libelle,
                               @RequestParam(required = false) String description, RedirectAttributes ra) {
        String lib = libelle == null ? "" : libelle.trim();
        if (lib.isEmpty()) {
            ra.addFlashAttribute("error", "Le libellé de la matière est obligatoire.");
        } else if (matiereRepository.existsByLibelleIgnoreCase(lib)) {
            ra.addFlashAttribute("error", "Cette matière existe déjà : " + lib);
        } else {
            matiereRepository.save(Matiere.builder().libelle(lib)
                    .description(description != null ? description.trim() : null).build());
            ra.addFlashAttribute("success", "Matière « " + lib + " » ajoutée.");
        }
        return "redirect:/admin/referentiels";
    }

    @PostMapping("/admin/matieres/{id}/supprimer")
    public String supprimerMatiere(@PathVariable Long id, RedirectAttributes ra) {
        try {
            matiereRepository.deleteById(id);
            ra.addFlashAttribute("success", "Matière supprimée.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Suppression impossible : cette matière est utilisée par des séances.");
        }
        return "redirect:/admin/referentiels";
    }

    // --- Classes ---
    @PostMapping("/admin/classes")
    public String creerClasse(@RequestParam String libelle,
                              @RequestParam(required = false) String filiere,
                              @RequestParam(required = false) Integer niveau, RedirectAttributes ra) {
        String lib = libelle == null ? "" : libelle.trim();
        if (lib.isEmpty()) {
            ra.addFlashAttribute("error", "Le libellé de la classe est obligatoire.");
        } else if (classeRepository.existsByLibelleIgnoreCase(lib)) {
            ra.addFlashAttribute("error", "Cette classe existe déjà : " + lib);
        } else {
            classeRepository.save(Classe.builder().libelle(lib)
                    .filiere(filiere != null ? filiere.trim() : null).niveau(niveau).build());
            ra.addFlashAttribute("success", "Classe « " + lib + " » ajoutée.");
        }
        return "redirect:/admin/referentiels";
    }

    @PostMapping("/admin/classes/{id}/supprimer")
    public String supprimerClasse(@PathVariable Long id, RedirectAttributes ra) {
        try {
            classeRepository.deleteById(id);
            ra.addFlashAttribute("success", "Classe supprimée.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Suppression impossible : cette classe est utilisée par des séances.");
        }
        return "redirect:/admin/referentiels";
    }

    // --- Salles ---
    @PostMapping("/admin/salles")
    public String creerSalle(@RequestParam String libelle, @RequestParam(required = false) String batiment,
                             @RequestParam(required = false) Integer capacite, RedirectAttributes ra) {
        String lib = libelle == null ? "" : libelle.trim().toUpperCase();
        if (lib.isEmpty()) {
            ra.addFlashAttribute("error", "Le nom de la salle est obligatoire.");
        } else if (!lib.matches("[A-Z0-9_\\-]{1,20}")) {
            // Le nom de salle sert de jeton dans le QR d'émargement (cf. QrController) :
            // il doit rester court et sans espace.
            ra.addFlashAttribute("error", "Le nom de salle doit être court et sans espace (lettres, chiffres, - ou _). Ex : A101.");
        } else if (salleRepository.existsByLibelleIgnoreCase(lib)) {
            ra.addFlashAttribute("error", "Cette salle existe déjà : " + lib);
        } else {
            salleRepository.save(Salle.builder().libelle(lib)
                    .batiment(batiment != null ? batiment.trim() : null).capacite(capacite).build());
            ra.addFlashAttribute("success", "Salle « " + lib + " » ajoutée.");
        }
        return "redirect:/admin/referentiels";
    }

    @PostMapping("/admin/salles/{id}/supprimer")
    public String supprimerSalle(@PathVariable Long id, RedirectAttributes ra) {
        try {
            salleRepository.deleteById(id);
            ra.addFlashAttribute("success", "Salle supprimée.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Suppression impossible : cette salle est utilisée par des séances.");
        }
        return "redirect:/admin/referentiels";
    }

    // --- Modèle d'emploi du temps (CSV téléchargeable) ---
    @GetMapping("/admin/planning/modele")
    public ResponseEntity<byte[]> modeleEmploiDuTemps() {
        String m  = matiereRepository.findAll().stream().findFirst().map(Matiere::getLibelle).orElse("MATIERE");
        String cl = classeRepository.findAll().stream().findFirst().map(Classe::getLibelle).orElse("CLASSE");
        String sa = salleRepository.findAll().stream().findFirst().map(Salle::getLibelle).orElse("SALLE");
        String today = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        StringBuilder sb = new StringBuilder();
        sb.append("DATE;HEURE_DEBUT;HEURE_FIN;MATIERE;CLASSE;SALLE\r\n");
        sb.append(today).append(";08:00;10:00;").append(m).append(';').append(cl).append(';').append(sa).append("\r\n");
        sb.append(today).append(";10:15;12:15;").append(m).append(';').append(cl).append(';').append(sa).append("\r\n");

        // BOM UTF-8 pour qu'Excel ouvre correctement les accents
        byte[] bytes = ("﻿" + sb).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"modele_emploi_du_temps.csv\"")
                .body(bytes);
    }

    // ─── FACULTES ─────────────────────────────────────────────────────────────

    @GetMapping("/admin/facultes")
    public String facultes(Model model) {
        model.addAttribute("facultes", faculteRepository.findAllByOrderByNomAsc());
        return "admin/facultes";
    }

    @PostMapping("/admin/facultes")
    public String creerFaculte(@RequestParam String code,
                               @RequestParam String nom,
                               @RequestParam(required = false) String description,
                               RedirectAttributes ra) {
        String codeClean = code == null ? "" : code.trim();
        String nomClean = nom == null ? "" : nom.trim();

        if (codeClean.isEmpty() || nomClean.isEmpty()) {
            ra.addFlashAttribute("error", "Le code et le nom de la faculté sont obligatoires.");
            return "redirect:/admin/facultes";
        }
        if (faculteRepository.existsByCodeIgnoreCase(codeClean)) {
            ra.addFlashAttribute("error", "Ce code de faculté existe déjà : " + codeClean);
            return "redirect:/admin/facultes";
        }
        if (faculteRepository.existsByNomIgnoreCase(nomClean)) {
            ra.addFlashAttribute("error", "Cette faculté existe déjà : " + nomClean);
            return "redirect:/admin/facultes";
        }

        Faculte faculte = Faculte.builder()
                .code(codeClean)
                .nom(nomClean)
                .description(description != null ? description.trim() : null)
                .build();
        faculteRepository.save(faculte);

        ra.addFlashAttribute("success", "Faculté « " + nomClean + " » ajoutée avec succès.");
        return "redirect:/admin/facultes";
    }

    @PostMapping("/admin/facultes/{id}/supprimer")
    public String supprimerFaculte(@PathVariable Long id, RedirectAttributes ra) {
        faculteRepository.findById(id).ifPresent(faculteRepository::delete);
        ra.addFlashAttribute("success", "Faculté supprimée.");
        return "redirect:/admin/facultes";
    }

    // ─── ENSEIGNANTS ──────────────────────────────────────────────────────────

    @GetMapping("/admin/enseignants")
    public String enseignants(@RequestParam(defaultValue = "") String search,
                              @RequestParam(defaultValue = "") String departement,
                              @RequestParam(defaultValue = "0") int page,
                              Model model) {
        int size = 10;
        String searchParam = search.isBlank() ? null : search;
        String deptParam = departement.isBlank() ? null : departement;

        Page<Enseignant> pageResult = enseignantRepository.searchEnseignants(
                searchParam, deptParam, PageRequest.of(page, size));

        model.addAttribute("enseignants", pageResult);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", pageResult.getTotalPages());
        model.addAttribute("totalElements", pageResult.getTotalElements());
        model.addAttribute("search", search);
        model.addAttribute("departement", departement);
        model.addAttribute("departements", enseignantRepository.findDistinctDepartements());

        return "admin/enseignants";
    }

    @GetMapping("/admin/enseignants/nouveau")
    public String nouvelEnseignantForm(Model model) {
        model.addAttribute("departements", enseignantRepository.findDistinctDepartements());
        return "admin/enseignant-form";
    }

    @PostMapping("/admin/enseignants")
    public String creerEnseignant(@RequestParam String matricule,
                                   @RequestParam String nom,
                                   @RequestParam String prenom,
                                   @RequestParam(required = false) String departement,
                                   @RequestParam(required = false) String grade,
                                   @RequestParam String email,
                                   @RequestParam String password,
                                   RedirectAttributes ra) {
        if (password == null || password.length() < 8
                || !password.matches("^(?=.*[A-Za-z])(?=.*\\d).+$")) {
            ra.addFlashAttribute("error",
                    "Le mot de passe doit faire au moins 8 caractères et contenir une lettre et un chiffre.");
            return "redirect:/admin/enseignants/nouveau";
        }
        if (enseignantRepository.existsByMatricule(matricule)) {
            ra.addFlashAttribute("error", "Ce matricule existe deja : " + matricule);
            return "redirect:/admin/enseignants/nouveau";
        }
        if (userRepository.existsByEmail(email)) {
            ra.addFlashAttribute("error", "Cet email est deja utilise : " + email);
            return "redirect:/admin/enseignants/nouveau";
        }

        Role role = roleRepository.findByName(ERole.ROLE_ENSEIGNANT).orElseThrow();

        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(password))
                .roles(Set.of(role))
                .build();
        userRepository.save(user);

        Enseignant enseignant = Enseignant.builder()
                .matricule(matricule)
                .nom(nom)
                .prenom(prenom)
                .departement(departement)
                .grade(grade)
                .statut(StatutEnseignant.PENDING)
                .user(user)
                .build();
        enseignantRepository.save(enseignant);

        ra.addFlashAttribute("success",
                "Enseignant " + prenom + " " + nom + " cree avec succes.");
        return "redirect:/admin/enseignants";
    }

    @PostMapping("/admin/enseignants/{id}/statut")
    public String updateStatut(@PathVariable Long id,
                               @RequestParam StatutEnseignant statut,
                               RedirectAttributes ra) {
        try {
            // Passe par le service → met à jour le statut ET notifie l'enseignant par e-mail
            enseignantService.updateStatut(id, statut);
            ra.addFlashAttribute("success", "Statut mis a jour.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Mise à jour impossible.");
        }
        return "redirect:/admin/enseignants";
    }

    @PostMapping("/admin/enseignants/{id}/supprimer")
    public String supprimerEnseignant(@PathVariable Long id, RedirectAttributes ra) {
        enseignantRepository.findById(id).ifPresent(enseignantRepository::delete);
        ra.addFlashAttribute("success", "Enseignant supprime.");
        return "redirect:/admin/enseignants";
    }

    // ─── RAPPORTS ────────────────────────────────────────────────────────────

    @GetMapping("/admin/rapports")
    public String rapports(@RequestParam(required = false) Long enseignantId,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin,
                           Model model) {
        model.addAttribute("rapports", rapportService.getRapportsFiltres(enseignantId, debut, fin));
        model.addAttribute("enseignants", enseignantRepository.findAll());
        // Valeurs des filtres (pour réafficher le formulaire et construire le lien ZIP)
        model.addAttribute("fEnseignantId", enseignantId);
        model.addAttribute("fDebut", debut);
        model.addAttribute("fFin", fin);
        return "admin/rapports";
    }

    /** Télécharge tous les rapports (filtrés) dans une archive ZIP. */
    @GetMapping("/admin/rapports/telecharger-zip")
    public ResponseEntity<byte[]> telechargerZip(
            @RequestParam(required = false) Long enseignantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin) {
        try {
            List<RapportPdf> rapports = rapportService.getRapportPdfsFiltres(enseignantId, debut, fin);
            if (rapports.isEmpty()) return ResponseEntity.noContent().build();
            byte[] zip = rapportService.genererZip(rapports);
            String nom = "rapports_" + LocalDate.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy")) + ".zip";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nom + "\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(zip);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/admin/rapports/generer")
    public String genererRapports(@RequestParam String debut,
                                   @RequestParam String fin,
                                   RedirectAttributes ra) {
        try {
            LocalDate d = LocalDate.parse(debut);
            LocalDate f = LocalDate.parse(fin);
            if (d.isAfter(f)) {
                ra.addFlashAttribute("error",
                        "La date de début doit être antérieure ou égale à la date de fin.");
                return "redirect:/admin/rapports";
            }
            List<?> result = rapportService.genererTousRapports(d, f);
            ra.addFlashAttribute("success", result.size() + " rapport(s) genere(s) avec succes.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Erreur lors de la generation : " + e.getMessage());
        }
        return "redirect:/admin/rapports";
    }

    @GetMapping("/admin/rapports/{id}/telecharger")
    public ResponseEntity<byte[]> telechargerRapport(@PathVariable Long id) {
        try {
            RapportPdf rapport = rapportService.getRapportEntity(id);
            byte[] bytes = rapportService.getRapportBytes(id);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + rapportService.nomFichierTelechargement(rapport) + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
