package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.StatutDemande;
import ci.esatic.sigep.entity.StatutSeance;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.DemandeRattrapageRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import ci.esatic.sigep.service.AiAnalyseService;
import ci.esatic.sigep.service.AlerteService;
import ci.esatic.sigep.service.DureeSeance;
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
    private final ci.esatic.sigep.repository.EmargementRepository emargementRepository;
    private final ci.esatic.sigep.service.CalendrierService calendrierService;

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
        // Six semaines d'activité lues en une fois : elles servent à la semaine en cours, à la
        // tendance et au taux par jour, qui demandaient chacun une requête par jour ou par semaine.
        LocalDate lundi = LocalDate.now().with(DayOfWeek.MONDAY);
        Map<LocalDate, long[]> parJour = activiteParJour(lundi.minusWeeks(5), lundi.plusDays(6));
        model.addAllAttributes(computeDashboardStats(parJour));
        model.addAttribute("activitesRecentes",
                rattrapageRepository.findAllByOrderByDateCreationDesc()
                        .stream().limit(6).toList());
        model.addAttribute("tendanceSemaines", computeTendanceSemaines(6, parJour));
        model.addAttribute("classement", computeClassement(30));
        ajouterRegistreDuJour(model);
        return "admin/dashboard";
    }

    /**
     * Le registre du jour et la phrase qui le résume.
     *
     * <p>Une heure n'est comptée qu'une fois la séance signée ET terminée ; une séance n'est à
     * relancer qu'une fois terminée sans signature : tant qu'elle dure, le scan peut encore venir.
     */
    private void ajouterRegistreDuJour(Model model) {
        LocalDate aujourdhui = LocalDate.now();
        java.time.LocalTime maintenant = java.time.LocalTime.now();
        List<ci.esatic.sigep.entity.Seance> seances = seanceRepository.findAllByDateOrderByHeureDebutAsc(aujourdhui)
                .stream().filter(s -> s.getStatut() != StatutSeance.A_VALIDER).toList();

        long signees = 0, aRelancer = 0, minutes = 0;
        for (ci.esatic.sigep.entity.Seance s : seances) {
            boolean signee = s.getStatut() == StatutSeance.EMARGE || s.getStatut() == StatutSeance.EN_RETARD;
            boolean finie = s.getHeureFin().isBefore(maintenant);
            if (signee) {
                signees++;
                if (finie) minutes += DureeSeance.de(s).minutes();
            } else if (finie && s.getStatut() != StatutSeance.EN_ATTENTE_VALIDATION) {
                aRelancer++;
            }
        }
        model.addAttribute("seancesDuJour", seances);
        model.addAttribute("signeesDuJour", signees);
        model.addAttribute("aRelancerDuJour", aRelancer);
        model.addAttribute("heuresDuJour", (minutes / 60) + " h " + String.format("%02d", minutes % 60));
        model.addAttribute("heureCourante", maintenant);
        model.addAttribute("seancesAValider", seanceRepository.countByStatut(StatutSeance.A_VALIDER));
        model.addAttribute("dateDuJour", aujourdhui.format(
                DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.FRENCH)));
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
        model.addAttribute("semestres", calendrierService.semestres());
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
        LocalDate lundi = LocalDate.now().with(DayOfWeek.MONDAY);
        return computeDashboardStats(activiteParJour(lundi, lundi.plusDays(6)));
    }

    /** Séances prévues et émargées de l'établissement, jour par jour, en une seule requête. */
    private Map<LocalDate, long[]> activiteParJour(LocalDate debut, LocalDate fin) {
        Map<LocalDate, long[]> parJour = new HashMap<>();
        for (Object[] l : seanceRepository.activiteParJour(debut, fin)) {
            parJour.put((LocalDate) l[0], new long[] {((Number) l[1]).longValue(), ((Number) l[2]).longValue()});
        }
        return parJour;
    }

    /** Somme des séances prévues [0] et émargées [1] entre deux jours inclus. */
    private static long[] cumul(Map<LocalDate, long[]> parJour, LocalDate debut, LocalDate fin) {
        long[] total = new long[2];
        for (LocalDate d = debut; !d.isAfter(fin); d = d.plusDays(1)) {
            long[] v = parJour.get(d);
            if (v != null) { total[0] += v[0]; total[1] += v[1]; }
        }
        return total;
    }

    private List<Map<String, Object>> computeTendanceSemaines(int nbSemaines, Map<LocalDate, long[]> parJour) {
        LocalDate lundiCourant = LocalDate.now().with(DayOfWeek.MONDAY);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = nbSemaines - 1; i >= 0; i--) {
            LocalDate lundi = lundiCourant.minusWeeks(i);
            long[] semaine = cumul(parJour, lundi, lundi.plusDays(6));
            long s = semaine[0];
            long e = semaine[1];
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
        // Une seule requête groupée : deux requêtes par enseignant faisaient d'un établissement
        // de cinquante enseignants une page à cent requêtes.
        for (Object[] l : seanceRepository.tauxParEnseignant(debut, fin)) {
            long total = ((Number) l[4]).longValue();
            if (total == 0) continue;
            long em = l[5] == null ? 0 : ((Number) l[5]).longValue();
            Map<String, Object> m = new HashMap<>();
            m.put("nom", l[1] + " " + l[2]);
            m.put("taux", Math.round(em * 1000.0 / total) / 10.0);
            out.add(m);
        }
        out.sort((a, b) -> Double.compare((double) b.get("taux"), (double) a.get("taux")));
        return out;
    }

    private Map<String, Object> computeDashboardStats(Map<LocalDate, long[]> parJour) {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);

        long[] semaine = cumul(parJour, monday, monday.plusDays(6));
        long seancesSemaine = semaine[0];
        long emargesSemaine = semaine[1];
        double tauxEmargement = seancesSemaine > 0
                ? Math.round(emargesSemaine * 1000.0 / seancesSemaine) / 10.0 : 0.0;

        double[] tauxParJour = new double[6];
        for (int i = 0; i < 6; i++) {
            LocalDate day = monday.plusDays(i);
            long[] v = parJour.getOrDefault(day, new long[] {0, 0});
            long s = v[0];
            long e = v[1];
            tauxParJour[i] = s > 0 ? Math.round(e * 1000.0 / s) / 10.0 : 0.0;
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalEnseignants", enseignantRepository.count());
        stats.put("totalSeancesAujourdhui", parJour.getOrDefault(today, new long[] {0, 0})[0]);
        stats.put("rattrapagesEnAttente", rattrapageRepository.countByStatut(StatutDemande.EN_ATTENTE));
        stats.put("emargementsHorsLigneCount", emargementRepository.countByHorsLigneTrueAndValideFalse());
        stats.put("tauxEmargement", tauxEmargement);
        double heures = seanceRepository.horairesEmargees(monday, monday.plusDays(6)).stream()
                .mapToDouble(r -> DureeSeance.de((java.time.LocalTime) r[1], (java.time.LocalTime) r[2]).heures()).sum();
        stats.put("heuresEffectuees", Math.round(heures * 10) / 10.0);
        stats.put("tauxParJour", tauxParJour);
        stats.put("alertesCount", alerteService.compter());
        return stats;
    }
}
