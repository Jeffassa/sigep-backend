package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import ci.esatic.sigep.service.RapportService;
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
    public String creerMatiere(@RequestParam String code, @RequestParam String libelle,
                               @RequestParam(required = false) String description, RedirectAttributes ra) {
        String c = code == null ? "" : code.trim().toUpperCase();
        if (c.isEmpty() || libelle == null || libelle.isBlank()) {
            ra.addFlashAttribute("error", "Le code et le libellé de la matière sont obligatoires.");
        } else if (matiereRepository.existsByCode(c)) {
            ra.addFlashAttribute("error", "Ce code matière existe déjà : " + c);
        } else {
            matiereRepository.save(Matiere.builder().code(c).libelle(libelle.trim())
                    .description(description != null ? description.trim() : null).build());
            ra.addFlashAttribute("success", "Matière « " + c + " » ajoutée.");
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
    public String creerClasse(@RequestParam String code, @RequestParam String libelle,
                              @RequestParam(required = false) String filiere,
                              @RequestParam(required = false) Integer niveau, RedirectAttributes ra) {
        String c = code == null ? "" : code.trim().toUpperCase();
        if (c.isEmpty() || libelle == null || libelle.isBlank()) {
            ra.addFlashAttribute("error", "Le code et le libellé de la classe sont obligatoires.");
        } else if (classeRepository.existsByCode(c)) {
            ra.addFlashAttribute("error", "Ce code classe existe déjà : " + c);
        } else {
            classeRepository.save(Classe.builder().code(c).libelle(libelle.trim())
                    .filiere(filiere != null ? filiere.trim() : null).niveau(niveau).build());
            ra.addFlashAttribute("success", "Classe « " + c + " » ajoutée.");
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
    public String creerSalle(@RequestParam String code, @RequestParam(required = false) String batiment,
                             @RequestParam(required = false) Integer capacite, RedirectAttributes ra) {
        String c = code == null ? "" : code.trim().toUpperCase();
        if (c.isEmpty()) {
            ra.addFlashAttribute("error", "Le code de la salle est obligatoire.");
        } else if (salleRepository.existsByCode(c)) {
            ra.addFlashAttribute("error", "Ce code salle existe déjà : " + c);
        } else {
            salleRepository.save(Salle.builder().code(c)
                    .batiment(batiment != null ? batiment.trim() : null).capacite(capacite).build());
            ra.addFlashAttribute("success", "Salle « " + c + " » ajoutée.");
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
        String m  = matiereRepository.findAll().stream().findFirst().map(Matiere::getCode).orElse("CODE_MATIERE");
        String cl = classeRepository.findAll().stream().findFirst().map(Classe::getCode).orElse("CODE_CLASSE");
        String sa = salleRepository.findAll().stream().findFirst().map(Salle::getCode).orElse("CODE_SALLE");
        String today = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        StringBuilder sb = new StringBuilder();
        sb.append("DATE;HEURE_DEBUT;HEURE_FIN;CODE_MATIERE;CODE_CLASSE;CODE_SALLE\r\n");
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
