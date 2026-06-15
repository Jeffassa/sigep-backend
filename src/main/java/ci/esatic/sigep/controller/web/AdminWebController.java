package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import ci.esatic.sigep.service.ImportService;
import ci.esatic.sigep.service.RapportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.multipart.MultipartFile;
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
    private final FaculteRepository faculteRepository;
    private final ImportService importService;

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
        enseignantRepository.findById(id).ifPresent(ens -> {
            ens.setStatut(statut);
            enseignantRepository.save(ens);
        });
        ra.addFlashAttribute("success", "Statut mis a jour.");
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
    public String rapports(Model model) {
        model.addAttribute("rapports", rapportService.getAllRapports());
        model.addAttribute("enseignants", enseignantRepository.findAll());
        return "admin/rapports";
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

    @PostMapping("/admin/import/planning")
    public String importPlanningWeb(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        if (file == null || file.isEmpty()) {
            ra.addFlashAttribute("error", "Veuillez sélectionner un fichier.");
            return "redirect:/admin/rapports";
        }
        String filename = file.getOriginalFilename();
        String lower = filename == null ? "" : filename.toLowerCase();
        if (!lower.endsWith(".csv") && !lower.endsWith(".xlsx")) {
            ra.addFlashAttribute("error", "Format non supporté. Utilisez .csv ou .xlsx.");
            return "redirect:/admin/rapports";
        }
        if (file.getSize() > 5 * 1024 * 1024L) {
            ra.addFlashAttribute("error", "Fichier trop volumineux (max 5 Mo).");
            return "redirect:/admin/rapports";
        }
        try {
            var result = importService.importerPlanning(file);
            ra.addFlashAttribute("success",
                    result.getOrDefault("totalImporte", 0) + " séance(s) importée(s) depuis " + filename + ".");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Le fichier contient des données invalides ou non reconnues.");
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
