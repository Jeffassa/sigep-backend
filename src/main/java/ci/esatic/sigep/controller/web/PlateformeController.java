package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.StatutEtablissement;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.service.AbonnementService;
import ci.esatic.sigep.service.MailService;
import ci.esatic.sigep.service.SuppressionEtablissementService;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Espace SUPER ADMIN (propriétaire de la plateforme) : vision et gestion GLOBALES des
 * établissements clients. Strictement séparé de l'admin d'établissement (/admin/**) :
 * rôle dédié ROLE_SUPER_ADMIN, navigation propre, et AUCUN filtre tenant (le
 * TenantInterceptor n'en pose pas pour ce rôle — lecture volontairement globale).
 * L'établissement technique « plateforme » (porteur du compte) n'est jamais listé.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequestMapping("/plateforme")
public class PlateformeController {

    private final EtablissementRepository etablissementRepository;
    private final EnseignantRepository enseignantRepository;
    private final UserRepository userRepository;
    private final AbonnementService abonnementService;
    private final MailService mailService;
    private final PlanService planService;
    private final SuppressionEtablissementService suppressionEtablissementService;

    /** Vue d'ensemble : dossiers à valider + KPIs (dont MRR) + liste filtrable des clients. */
    @GetMapping({"", "/"})
    public String dashboard(@RequestParam(required = false) String q,
                            @RequestParam(required = false) String plan,
                            @RequestParam(required = false) String statut,
                            Model model) {
        List<Etablissement> tous = clients();

        Map<Long, Long> enseignantsParEtab = new HashMap<>();
        for (Object[] ligne : enseignantRepository.countParEtablissement()) {
            enseignantsParEtab.put((Long) ligne[0], (Long) ligne[1]);
        }

        Set<Long> expires = tous.stream()
                .filter(abonnementService::estExpire)
                .map(Etablissement::getId)
                .collect(Collectors.toSet());
        Map<String, Long> parPlan = tous.stream()
                .collect(Collectors.groupingBy(e -> e.getPlan().name(), Collectors.counting()));

        // KPIs business : MRR estimé (abonnements payants actifs) + inscriptions du mois.
        long mrr = tous.stream()
                .filter(e -> e.getStatut() == StatutEtablissement.VALIDE && e.isActif() && !expires.contains(e.getId()))
                .mapToLong(e -> planService.prixMensuel(e.getPlan()))
                .sum();
        LocalDate now = LocalDate.now();
        long inscriptionsMois = tous.stream()
                .filter(e -> e.getDateCreation() != null
                        && e.getDateCreation().getYear() == now.getYear()
                        && e.getDateCreation().getMonthValue() == now.getMonthValue())
                .count();

        // SA-2 : dossiers en attente (toujours affichés, hors filtre).
        List<Etablissement> enAttente = tous.stream()
                .filter(e -> e.getStatut() == StatutEtablissement.EN_ATTENTE)
                .toList();
        Map<Long, String> adminParEtab = new HashMap<>();
        for (Etablissement e : enAttente) {
            userRepository.findFirstByEtablissementIdOrderByIdAsc(e.getId())
                    .ifPresent(u -> adminParEtab.put(e.getId(), u.getEmail()));
        }

        // Recherche + filtres sur la liste principale.
        String recherche = q == null ? "" : q.trim().toLowerCase();
        List<Etablissement> filtres = tous.stream()
                .filter(e -> recherche.isEmpty()
                        || e.getNom().toLowerCase().contains(recherche)
                        || e.getSlug().toLowerCase().contains(recherche))
                .filter(e -> plan == null || plan.isBlank() || e.getPlan().name().equals(plan))
                .filter(e -> statut == null || statut.isBlank() || e.getStatut().name().equals(statut))
                .toList();

        model.addAttribute("etablissements", filtres);
        model.addAttribute("enseignantsParEtab", enseignantsParEtab);
        model.addAttribute("expires", expires);
        model.addAttribute("parPlan", parPlan);
        model.addAttribute("nbEtablissements", tous.size());
        model.addAttribute("nbActifs", tous.stream().filter(Etablissement::isActif).count());
        model.addAttribute("nbExpires", (long) expires.size());
        model.addAttribute("mrr", mrr);
        model.addAttribute("inscriptionsMois", inscriptionsMois);
        model.addAttribute("enAttente", enAttente);
        model.addAttribute("adminParEtab", adminParEtab);
        model.addAttribute("q", q);
        model.addAttribute("fPlan", plan);
        model.addAttribute("fStatut", statut);
        return "plateforme/dashboard";
    }

    /** Fiche détaillée d'un établissement : infos, administrateurs, effectif + actions. */
    @GetMapping("/etablissements/{slug}")
    public String fiche(@PathVariable String slug, Model model, RedirectAttributes ra) {
        Etablissement e = etablissementRepository.findBySlug(slug).orElse(null);
        if (e == null || DataInitializer.SLUG_PLATEFORME.equals(slug)) {
            ra.addFlashAttribute("erreur", "Établissement introuvable : " + slug);
            return "redirect:/plateforme";
        }
        model.addAttribute("e", e);
        model.addAttribute("admins", userRepository.findByEtablissementIdOrderByIdAsc(e.getId()));
        model.addAttribute("nbEnseignants", enseignantRepository.countByEtablissementId(e.getId()));
        model.addAttribute("expire", abonnementService.estExpire(e));
        model.addAttribute("joursAvantExpiration", abonnementService.joursAvantExpiration(e));
        model.addAttribute("prixMensuel", planService.prixMensuel(e.getPlan()));
        return "plateforme/etablissement";
    }

    /** Suspendre / réactiver l'accès d'un établissement. */
    @PostMapping("/etablissements/actif")
    @Transactional
    public String changerActivation(@RequestParam String slug, @RequestParam boolean actif,
                                    RedirectAttributes ra) {
        modifier(slug, ra, e -> {
            e.setActif(actif);
            ra.addFlashAttribute("ok", "« " + e.getNom() + " » " + (actif ? "réactivé." : "suspendu."));
        });
        return "redirect:/plateforme/etablissements/" + slug;
    }

    /** Changer le plan d'un établissement (ajuste le quota par défaut du plan). */
    @PostMapping("/etablissements/plan")
    @Transactional
    public String changerPlan(@RequestParam String slug, @RequestParam Plan plan, RedirectAttributes ra) {
        modifier(slug, ra, e -> {
            e.setPlan(plan);
            e.setMaxEnseignants(plan == Plan.FREE ? 10 : 0); // Pro/Enterprise = illimité
            ra.addFlashAttribute("ok", "Plan de « " + e.getNom() + " » changé en " + plan + ".");
        });
        return "redirect:/plateforme/etablissements/" + slug;
    }

    /** Ajuster le quota d'enseignants (0 = illimité). */
    @PostMapping("/etablissements/quota")
    @Transactional
    public String changerQuota(@RequestParam String slug, @RequestParam int maxEnseignants,
                               RedirectAttributes ra) {
        modifier(slug, ra, e -> {
            e.setMaxEnseignants(Math.max(0, maxEnseignants));
            ra.addFlashAttribute("ok", "Quota de « " + e.getNom() + " » réglé à "
                    + (maxEnseignants <= 0 ? "illimité" : maxEnseignants) + ".");
        });
        return "redirect:/plateforme/etablissements/" + slug;
    }

    /** Suppression RGPD : efface l'établissement et TOUTES ses données. Irréversible. */
    @PostMapping("/etablissements/supprimer")
    @Transactional
    public String supprimer(@RequestParam String slug, RedirectAttributes ra) {
        Etablissement e = etablissementRepository.findBySlug(slug).orElse(null);
        if (e == null || DataInitializer.SLUG_PLATEFORME.equals(slug)) {
            ra.addFlashAttribute("erreur", "Action impossible sur « " + slug + " ».");
            return "redirect:/plateforme";
        }
        String nom = e.getNom();
        suppressionEtablissementService.supprimerToutesLesDonnees(e.getId());
        ra.addFlashAttribute("ok", "« " + nom + " » et toutes ses données ont été supprimés.");
        return "redirect:/plateforme";
    }

    /** Applique une modification à un établissement client (jamais l'établissement plateforme). */
    private void modifier(String slug, RedirectAttributes ra, java.util.function.Consumer<Etablissement> action) {
        if (DataInitializer.SLUG_PLATEFORME.equals(slug)) {
            ra.addFlashAttribute("erreur", "Action non autorisée sur l'établissement plateforme.");
            return;
        }
        etablissementRepository.findBySlug(slug).ifPresentOrElse(e -> {
            action.accept(e);
            etablissementRepository.save(e);
        }, () -> ra.addFlashAttribute("erreur", "Établissement introuvable : " + slug));
    }

    /** SA-2 : valider un dossier d'inscription — l'établissement peut alors se connecter. */
    @PostMapping("/etablissements/valider")
    @Transactional
    public String validerEtablissement(@RequestParam String slug, RedirectAttributes ra) {
        etablissementRepository.findBySlug(slug).ifPresentOrElse(e -> {
            e.setStatut(StatutEtablissement.VALIDE);
            etablissementRepository.save(e);
            userRepository.findFirstByEtablissementIdOrderByIdAsc(e.getId())
                    .ifPresent(u -> mailService.notifierEtablissementValide(u.getEmail(), e.getNom()));
            ra.addFlashAttribute("ok", "« " + e.getNom() + " » validé : l'administrateur a été prévenu par e-mail.");
        }, () -> ra.addFlashAttribute("erreur", "Établissement introuvable : " + slug));
        return "redirect:/plateforme";
    }

    /** SA-2 : refuser un dossier d'inscription — la connexion reste bloquée. */
    @PostMapping("/etablissements/refuser")
    @Transactional
    public String refuserEtablissement(@RequestParam String slug, RedirectAttributes ra) {
        etablissementRepository.findBySlug(slug).ifPresentOrElse(e -> {
            e.setStatut(StatutEtablissement.REFUSE);
            etablissementRepository.save(e);
            userRepository.findFirstByEtablissementIdOrderByIdAsc(e.getId())
                    .ifPresent(u -> mailService.notifierEtablissementRefuse(u.getEmail(), e.getNom()));
            ra.addFlashAttribute("ok", "« " + e.getNom() + " » refusé : l'administrateur a été prévenu par e-mail.");
        }, () -> ra.addFlashAttribute("erreur", "Établissement introuvable : " + slug));
        return "redirect:/plateforme";
    }

    /** Renouvellements : valider un paiement reçu = prolonger la période d'un établissement. */
    @GetMapping("/abonnements")
    public String abonnements(Model model) {
        List<Etablissement> etablissements = clients();
        Set<Long> expires = etablissements.stream()
                .filter(abonnementService::estExpire)
                .map(Etablissement::getId)
                .collect(Collectors.toSet());
        model.addAttribute("etablissements", etablissements);
        model.addAttribute("expires", expires);
        return "plateforme/abonnements";
    }

    /** Prolongation manuelle après paiement constaté (Mobile Money). */
    @PostMapping("/abonnements/prolonger")
    @Transactional
    public String prolonger(@RequestParam String slug,
                            @RequestParam(defaultValue = "1") int mois,
                            RedirectAttributes ra) {
        etablissementRepository.findBySlug(slug).ifPresentOrElse(e -> {
            abonnementService.prolonger(e, Math.max(1, mois));
            etablissementRepository.save(e);
            ra.addFlashAttribute("ok", "Abonnement de « " + e.getNom() + " » prolongé de "
                    + Math.max(1, mois) + " mois (jusqu'au " + e.getDateExpiration() + ").");
        }, () -> ra.addFlashAttribute("erreur", "Établissement introuvable : " + slug));
        return "redirect:/plateforme/abonnements";
    }

    /** Établissements clients (l'établissement technique « plateforme » est exclu). */
    private List<Etablissement> clients() {
        return etablissementRepository.findAll().stream()
                .filter(e -> !DataInitializer.SLUG_PLATEFORME.equals(e.getSlug()))
                .toList();
    }
}
