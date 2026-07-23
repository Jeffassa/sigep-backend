package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.StatutDemande;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.DemandeRattrapageRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import ci.esatic.sigep.service.AiAnalyseService;
import ci.esatic.sigep.service.AlerteService;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.service.StatistiquesService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Espace admin : connexion, tableau de bord, statistiques et analyse IA. */
@Controller
@RequiredArgsConstructor
public class AdminDashboardController {

    private final SeanceRepository seanceRepository;
    private final DemandeRattrapageRepository rattrapageRepository;
    private final EnseignantRepository enseignantRepository;
    private final StatistiquesService statistiquesService;
    private final AiAnalyseService aiAnalyseService;
    private final PlanService planService;
    private final EtablissementCourantService etablissementCourantService;
    private final AlerteService alerteService;

    @GetMapping("/admin-login")
    public String loginPage(@RequestParam(required = false) String error,
                            @RequestParam(required = false) String logout,
                            Model model) {
        if (error != null) model.addAttribute("error", "Identifiants incorrects.");
        if (logout != null) model.addAttribute("logout", "Deconnexion reussie.");
        return "admin/login";
    }

    @GetMapping({"/admin", "/admin/"})
    public String adminRoot() {
        return "redirect:/admin/dashboard";
    }

    @GetMapping("/admin/dashboard")
    public String dashboard(Model model) {
        model.addAllAttributes(computeDashboardStats());
        model.addAttribute("activitesRecentes",
                rattrapageRepository.findAllByOrderByDateCreationDesc()
                        .stream().limit(6).toList());
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
        model.addAllAttributes(statistiquesService.computeStatistiques(d, f));
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
            @AuthenticationPrincipal User admin) {
        Map<String, Object> out = new HashMap<>();
        // Gating premium : l'analyse IA n'est disponible que pour les plans qui l'incluent (PRO+).
        if (admin == null || !planService.estDisponible(etablissementCourantService.courant(), Feature.ANALYSE_IA)) {
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
        Map<String, Object> stats = statistiquesService.computeStatistiques(d, f);
        out.put("texte", aiAnalyseService.analyser(d, f, stats));
        return out;
    }

    /** Données du dashboard en JSON, consommées par l'auto-actualisation côté client. */
    @GetMapping("/admin/api/stats")
    @ResponseBody
    public Map<String, Object> dashboardStats() {
        return computeDashboardStats();
    }

    private List<Map<String, Object>> computeTendanceSemaines(int nbSemaines) {
        LocalDate lundiCourant = LocalDate.now().with(DayOfWeek.MONDAY);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = nbSemaines - 1; i >= 0; i--) {
            LocalDate lundi = lundiCourant.minusWeeks(i);
            LocalDate dimanche = lundi.plusDays(6);
            long s = seanceRepository.countAllByDateBetween(lundi, dimanche);
            long e = seanceRepository.countAllEmargesByDateBetween(lundi, dimanche);
            Map<String, Object> m = new HashMap<>();
            m.put("label", lundi.format(DateTimeFormatter.ofPattern("dd/MM")));
            m.put("taux", s > 0 ? Math.round(e * 1000.0 / s) / 10.0 : 0.0);
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> computeClassement(int jours) {
        LocalDate fin = LocalDate.now();
        LocalDate debut = fin.minusDays(jours);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Enseignant e : enseignantRepository.findAll()) {
            long total = seanceRepository.countByEnseignantIdAndPeriode(e.getId(), debut, fin);
            if (total == 0) continue;
            long em = seanceRepository.countEmargeesParEnseignant(e.getId(), debut, fin);
            Map<String, Object> m = new HashMap<>();
            m.put("nom", e.getPrenom() + " " + e.getNom());
            m.put("taux", Math.round(em * 1000.0 / total) / 10.0);
            out.add(m);
        }
        out.sort((a, b) -> Double.compare((double) b.get("taux"), (double) a.get("taux")));
        return out;
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
        stats.put("alertesCount", alerteService.compter());
        return stats;
    }
}
