package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import ci.esatic.sigep.service.RapportService;
import ci.esatic.sigep.service.EnseignantService;
import ci.esatic.sigep.service.ImportService;
import ci.esatic.sigep.service.MailService;
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
    private final ImportService importService;
    private final MailService mailService;
    private final MatiereRepository matiereRepository;
    private final ClasseRepository classeRepository;
    private final SalleRepository salleRepository;
    private final ci.esatic.sigep.service.RattrapageService rattrapageService;
    private final ci.esatic.sigep.service.StatsService statsService;
    private final ci.esatic.sigep.service.AiAnalyseService aiAnalyseService;
    private final ci.esatic.sigep.tenant.plan.PlanService planService;

    // ─── COMMUN ───────────────────────────────────────────────────────────────

    /** Compteur d'alertes exposé à toutes les vues admin (badge de la barre supérieure). */
    @ModelAttribute("alertesCount")
    public long alertesCount() {
        return compterAlertes();
    }

    private long compterAlertes() {
        return seanceRepository.countSeancesNonEmargees(LocalDate.now(), java.time.LocalTime.now())
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
        // Tendance multi-semaines + classement (calculés au chargement, pas dans le refresh 1s)
        model.addAttribute("tendanceSemaines", computeTendanceSemaines(6));
        model.addAttribute("classement", computeClassement(30));
        return "admin/dashboard";
    }

    /** Page de statistiques complètes (KPIs, tendances, répartitions, heatmap, recommandations). */
    @GetMapping("/admin/statistiques")
    public String statistiques(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin,
            @RequestParam(required = false) String periode,
            Model model) {
        LocalDate today = LocalDate.now();
        LocalDate d;
        LocalDate f;
        if (debut != null && fin != null && !debut.isAfter(fin)) {
            d = debut;
            f = fin;
            periode = "perso";
        } else if ("semaine".equals(periode)) {
            d = today.with(DayOfWeek.MONDAY);
            f = d.plusDays(6);
        } else { // mois en cours (par défaut)
            d = today.withDayOfMonth(1);
            f = today.withDayOfMonth(today.lengthOfMonth());
            periode = "mois";
        }
        model.addAllAttributes(statsService.computeStatistiques(d, f));
        model.addAttribute("periode", periode);
        model.addAttribute("aiEnabled", aiAnalyseService.isEnabled());
        return "admin/statistiques";
    }

    /** Analyse IA (synthèse + décisions) des statistiques de la période. JSON pour le bouton. */
    @GetMapping("/admin/api/stats/analyse")
    @ResponseBody
    public Map<String, Object> analyseIa(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin,
            @RequestParam(required = false) String periode,
            @org.springframework.security.core.annotation.AuthenticationPrincipal User admin) {
        Map<String, Object> out = new HashMap<>();
        // Gating premium : l'analyse IA n'est disponible que pour les plans qui l'incluent (PRO+).
        if (admin == null || !planService.estDisponible(admin.getEtablissement(),
                ci.esatic.sigep.tenant.plan.Feature.ANALYSE_IA)) {
            out.put("texte", "L'analyse IA est réservée à un plan supérieur (PRO ou Enterprise). "
                    + "Passez à un plan supérieur pour l'activer.");
            return out;
        }
        LocalDate today = LocalDate.now();
        LocalDate d;
        LocalDate f;
        if (debut != null && fin != null && !debut.isAfter(fin)) {
            d = debut;
            f = fin;
        } else if ("semaine".equals(periode)) {
            d = today.with(DayOfWeek.MONDAY);
            f = d.plusDays(6);
        } else {
            d = today.withDayOfMonth(1);
            f = today.withDayOfMonth(today.lengthOfMonth());
        }
        Map<String, Object> stats = statsService.computeStatistiques(d, f);
        out.put("texte", aiAnalyseService.analyser(d, f, stats));
        return out;
    }

    /** Taux d'émargement par semaine sur les {@code nbSemaines} dernières semaines. */
    private List<Map<String, Object>> computeTendanceSemaines(int nbSemaines) {
        LocalDate lundiCourant = LocalDate.now().with(DayOfWeek.MONDAY);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (int i = nbSemaines - 1; i >= 0; i--) {
            LocalDate lundi = lundiCourant.minusWeeks(i);
            LocalDate dimanche = lundi.plusDays(6);
            long s = seanceRepository.countAllByDateBetween(lundi, dimanche);
            long e = seanceRepository.countAllEmargesByDateBetween(lundi, dimanche);
            Map<String, Object> m = new HashMap<>();
            m.put("label", lundi.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")));
            m.put("taux", s > 0 ? Math.round(e * 1000.0 / s) / 10.0 : 0.0);
            out.add(m);
        }
        return out;
    }

    /** Classement des enseignants par taux d'émargement sur les {@code jours} derniers jours. */
    private List<Map<String, Object>> computeClassement(int jours) {
        LocalDate fin = LocalDate.now();
        LocalDate debut = fin.minusDays(jours);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Enseignant e : enseignantRepository.findAll()) {
            long total = seanceRepository.countByEnseignantIdAndPeriode(e.getId(), debut, fin);
            if (total == 0) continue; // on ne classe que les profs ayant eu des séances
            long em = seanceRepository.countEmargeesParEnseignant(e.getId(), debut, fin);
            Map<String, Object> m = new HashMap<>();
            m.put("nom", e.getPrenom() + " " + e.getNom());
            m.put("taux", Math.round(em * 1000.0 / total) / 10.0);
            out.add(m);
        }
        out.sort((a, b) -> Double.compare((double) b.get("taux"), (double) a.get("taux")));
        return out;
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
        model.addAttribute("seancesNonEmargees", seanceRepository.findSeancesNonEmargees(LocalDate.now(), java.time.LocalTime.now()));
        model.addAttribute("rattrapagesEnAttente",
                rattrapageRepository.findByStatutOrderByDateCreationDesc(StatutDemande.EN_ATTENTE));
        model.addAttribute("enseignantsEnAttente",
                enseignantRepository.findByStatutOrderByNomAsc(StatutEnseignant.PENDING));
        model.addAttribute("salles", salleRepository.findAll());
        return "admin/alertes";
    }

    /** Accepte une demande de rattrapage : crée la séance dans la salle choisie + email à l'enseignant. */
    @PostMapping("/admin/rattrapages/{id}/accepter")
    public String accepterRattrapage(@PathVariable Long id, @RequestParam Long salleId,
                                     RedirectAttributes ra) {
        try {
            ci.esatic.sigep.entity.Salle salle = salleRepository.findById(salleId)
                    .orElseThrow(() -> new IllegalArgumentException("Salle introuvable"));
            rattrapageService.accepterAvecSalle(id, salle);
            ra.addFlashAttribute("success",
                    "Demande acceptee : seance de rattrapage creee, enseignant notifie par email.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Erreur : " + e.getMessage());
        }
        return "redirect:/admin/alertes";
    }

    /** Refuse une demande de rattrapage + email à l'enseignant. */
    @PostMapping("/admin/rattrapages/{id}/refuser")
    public String refuserRattrapage(@PathVariable Long id, RedirectAttributes ra) {
        try {
            rattrapageService.refuser(id);
            ra.addFlashAttribute("success", "Demande refusee : enseignant notifie par email.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Erreur : " + e.getMessage());
        }
        return "redirect:/admin/alertes";
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
                searchParam, deptParam, ci.esatic.sigep.tenant.TenantContext.get(), PageRequest.of(page, size));

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
    @org.springframework.transaction.annotation.Transactional
    public String creerEnseignant(@org.springframework.security.core.annotation.AuthenticationPrincipal User admin,
                                   @RequestParam String matricule,
                                   @RequestParam String nom,
                                   @RequestParam String prenom,
                                   @RequestParam(required = false) String departement,
                                   @RequestParam(required = false) String grade,
                                   @RequestParam String email,
                                   @RequestParam String password,
                                   RedirectAttributes ra) {
        // Quota du plan (Free ≤ 10 enseignants) : appliqué sur TOUS les chemins de création.
        if (admin != null && admin.getEtablissement() != null
                && planService.quotaEnseignantsAtteint(admin.getEtablissement(),
                    enseignantRepository.countByEtablissementId(admin.getEtablissement().getId()))) {
            ra.addFlashAttribute("error", "Quota d'enseignants atteint ("
                    + admin.getEtablissement().getMaxEnseignants()
                    + ") pour le plan " + admin.getEtablissement().getPlan()
                    + ". Passez à un plan supérieur pour en ajouter davantage.");
            return "redirect:/admin/enseignants";
        }
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

    // Import en masse d'enseignants (Excel) : crée l'annuaire ; les profs s'inscrivent ensuite par matricule.
    @PostMapping("/admin/enseignants/import")
    public String importerEnseignants(@RequestParam("fichier") MultipartFile fichier, RedirectAttributes ra) {
        try {
            Map<String, Object> r = importService.importerEnseignants(fichier);
            String message = r.get("importes") + " enseignant(s) importé(s), "
                    + r.get("ignores") + " ignoré(s) (déjà présents).";
            @SuppressWarnings("unchecked")
            java.util.List<Integer> invalides = (java.util.List<Integer>) r.get("lignesInvalides");
            if (invalides != null && !invalides.isEmpty()) {
                message += " " + invalides.size() + " ligne(s) incomplète(s) non importée(s) : " + invalides + ".";
            }
            if (Boolean.TRUE.equals(r.get("quotaAtteint"))) {
                message += " Quota d'enseignants du plan atteint : le reste du fichier n'a pas été importé"
                        + " — passez à un plan supérieur pour continuer.";
            }
            ra.addFlashAttribute("success", message);
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Import impossible : " + e.getMessage());
        }
        return "redirect:/admin/enseignants";
    }

    // --- Messagerie : e-mail à un enseignant précis ou à tous ---
    @GetMapping("/admin/messages")
    public String messagesForm(Model model) {
        model.addAttribute("enseignants", enseignantRepository.findAll().stream()
                .filter(e -> e.getUser() != null)
                .toList());
        return "admin/messages";
    }

    @PostMapping("/admin/messages")
    public String envoyerMessage(@RequestParam String destinataire,
                                 @RequestParam String sujet,
                                 @RequestParam String corps,
                                 RedirectAttributes ra) {
        int envoyes = 0;
        if ("ALL".equals(destinataire)) {
            for (Enseignant e : enseignantRepository.findAll()) {
                if (e.getUser() != null && e.getUser().getEmail() != null) {
                    mailService.envoyerMessage(e.getUser().getEmail(), sujet, corps);
                    envoyes++;
                }
            }
        } else {
            Enseignant e = enseignantRepository.findById(Long.valueOf(destinataire)).orElse(null);
            if (e != null && e.getUser() != null) {
                mailService.envoyerMessage(e.getUser().getEmail(), sujet, corps);
                envoyes = 1;
            }
        }
        ra.addFlashAttribute("success", envoyes + " message(s) envoyé(s).");
        return "redirect:/admin/messages";
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

    /** Télécharge tous les rapports (filtrés) dans une archive ZIP. Fonction Pro/Enterprise :
     *  verrouillée côté serveur (le bouton masqué en Free ne suffit pas — URL directe possible). */
    @GetMapping("/admin/rapports/telecharger-zip")
    public ResponseEntity<byte[]> telechargerZip(
            @org.springframework.security.core.annotation.AuthenticationPrincipal User admin,
            @RequestParam(required = false) Long enseignantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin) {
        if (admin == null || !planService.estDisponible(admin.getEtablissement(),
                ci.esatic.sigep.tenant.plan.Feature.RAPPORTS_AVANCES)) {
            return ResponseEntity.status(403).build();
        }
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

    /** Export Excel de synthèse (une ligne par enseignant sur la période). Fonction
     *  Pro/Enterprise : verrouillée côté serveur (pas seulement masquée dans l'interface). */
    @GetMapping("/admin/rapports/synthese")
    public ResponseEntity<byte[]> syntheseExcel(
            @org.springframework.security.core.annotation.AuthenticationPrincipal User admin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin) {
        if (admin == null || !planService.estDisponible(admin.getEtablissement(),
                ci.esatic.sigep.tenant.plan.Feature.RAPPORTS_AVANCES)) {
            return ResponseEntity.status(403).build();
        }
        try {
            LocalDate today = LocalDate.now();
            LocalDate d = debut != null ? debut : today.with(DayOfWeek.MONDAY);
            LocalDate f = fin != null ? fin : d.plusDays(6);
            byte[] xlsx = rapportService.genererSyntheseExcel(d, f);
            var fmt = java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy");
            String nom = "synthese_" + d.format(fmt) + "-" + f.format(fmt) + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nom + "\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(xlsx);
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
