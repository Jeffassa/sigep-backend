package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Paiement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.StatutEtablissement;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.PaiementRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.service.AbonnementService;
import ci.esatic.sigep.service.MailService;
import ci.esatic.sigep.service.OnboardingService;
import ci.esatic.sigep.service.SuppressionEtablissementService;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private final OnboardingService onboardingService;
    private final PlanService planService;
    private final SuppressionEtablissementService suppressionEtablissementService;
    private final PaiementRepository paiementRepository;

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
        model.addAttribute("totalEncaisse", paiementRepository.totalEncaisse());
        model.addAttribute("encaisseMois", paiementRepository.encaisseDepuis(
                now.withDayOfMonth(1).atStartOfDay()));
        model.addAttribute("enAttente", enAttente);
        model.addAttribute("adminParEtab", adminParEtab);
        model.addAttribute("q", q);
        model.addAttribute("fPlan", plan);
        model.addAttribute("fStatut", statut);

        // ===== Données pour les graphiques (data-viz) — toutes issues du système réel =====
        // Entonnoir d'onboarding : Inscrits → Validés → Actifs → Abonnés (payants, non expirés).
        long fInscrits = tous.size();
        long fValides  = tous.stream().filter(e -> e.getStatut() == StatutEtablissement.VALIDE).count();
        long fActifs   = tous.stream().filter(e -> e.getStatut() == StatutEtablissement.VALIDE && e.isActif()).count();
        long fAbonnes  = tous.stream().filter(e -> e.getStatut() == StatutEtablissement.VALIDE
                && e.isActif() && !expires.contains(e.getId())).count();
        model.addAttribute("funnelNoms", List.of("Inscrits", "Validés", "Actifs", "Abonnés"));
        model.addAttribute("funnelValeurs", List.of(fInscrits, fValides, fActifs, fAbonnes));

        // Répartition par plan (ordre fixe, 0 si absent).
        List<String> planNoms = List.of("FREE", "PRO", "ENTERPRISE");
        model.addAttribute("planNoms", planNoms);
        model.addAttribute("planValeurs", planNoms.stream().map(n -> parPlan.getOrDefault(n, 0L)).toList());

        // Série sur les 8 derniers mois : encaissements réels + nouveaux établissements.
        List<YearMonth> derniersMois = new ArrayList<>();
        YearMonth courant = YearMonth.now();
        for (int i = 7; i >= 0; i--) derniersMois.add(courant.minusMonths(i));

        Map<YearMonth, Long> encParMois = new HashMap<>();
        for (var p : paiementRepository.findAllByOrderByDatePaiementDesc()) {
            if (p.getDatePaiement() != null) {
                encParMois.merge(YearMonth.from(p.getDatePaiement()), p.getMontant(), Long::sum);
            }
        }
        Map<YearMonth, Long> nouvParMois = new HashMap<>();
        for (Etablissement e : tous) {
            if (e.getDateCreation() != null) {
                nouvParMois.merge(YearMonth.from(e.getDateCreation()), 1L, Long::sum);
            }
        }
        List<String> moisLabels = new ArrayList<>();
        List<Long> encaisseParMois = new ArrayList<>();
        List<Long> nouveauxParMois = new ArrayList<>();
        for (YearMonth m : derniersMois) {
            String lbl = m.getMonth().getDisplayName(TextStyle.SHORT, Locale.FRENCH).replace(".", "");
            if (!lbl.isEmpty()) lbl = Character.toUpperCase(lbl.charAt(0)) + lbl.substring(1);
            moisLabels.add(lbl);
            encaisseParMois.add(encParMois.getOrDefault(m, 0L));
            nouveauxParMois.add(nouvParMois.getOrDefault(m, 0L));
        }
        model.addAttribute("moisLabels", moisLabels);
        model.addAttribute("encaisseParMois", encaisseParMois);
        model.addAttribute("nouveauxParMois", nouveauxParMois);

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
        model.addAttribute("paiements", paiementRepository.findByEtablissementIdOrderByDatePaiementDesc(e.getId()));
        model.addAttribute("totalPaye", paiementRepository.totalParEtablissement(e.getId()));
        return "plateforme/etablissement";
    }

    /** Formulaire de création directe d'un établissement (super-admin). */
    @GetMapping("/etablissements/nouveau")
    public String nouveauEtablissementForm(Model model) {
        model.addAttribute("plans", Plan.values());
        return "plateforme/etablissement-nouveau";
    }

    /** Création directe d'un établissement (VALIDE, actif) + son premier administrateur. */
    @PostMapping("/etablissements/creer")
    public String creerEtablissement(@RequestParam String nom,
                                     @RequestParam String adminEmail,
                                     @RequestParam String adminPassword,
                                     @RequestParam(required = false) String adminNom,
                                     @RequestParam(required = false) String adminPrenom,
                                     @RequestParam(required = false) Plan plan,
                                     RedirectAttributes ra) {
        if (nom == null || nom.isBlank() || adminEmail == null || adminEmail.isBlank()
                || adminPassword == null || adminPassword.length() < 8) {
            ra.addFlashAttribute("erreur",
                    "Nom de l'établissement, e-mail admin et mot de passe (≥ 8 caractères) sont requis.");
            return "redirect:/plateforme/etablissements/nouveau";
        }
        try {
            Etablissement e = onboardingService.creerParSuperAdmin(
                    nom, adminEmail.trim(), adminPassword, adminNom, adminPrenom, plan);
            ra.addFlashAttribute("ok", "« " + e.getNom() + " » créé et activé (plan " + e.getPlan()
                    + "). L'administrateur peut se connecter : " + adminEmail.trim() + ".");
            return "redirect:/plateforme/etablissements/" + e.getSlug();
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("erreur", ex.getMessage());
            return "redirect:/plateforme/etablissements/nouveau";
        }
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

    /** Renouvellements : valider un paiement reçu = enregistrer le paiement + prolonger. */
    @GetMapping("/abonnements")
    public String abonnements(Model model) {
        List<Etablissement> etablissements = clients();
        Set<Long> expires = etablissements.stream()
                .filter(abonnementService::estExpire)
                .map(Etablissement::getId)
                .collect(Collectors.toSet());
        Map<Long, Long> tarifParEtab = etablissements.stream()
                .collect(Collectors.toMap(Etablissement::getId, e -> planService.prixMensuel(e.getPlan())));
        model.addAttribute("etablissements", etablissements);
        model.addAttribute("expires", expires);
        model.addAttribute("tarifParEtab", tarifParEtab);
        return "plateforme/abonnements";
    }

    /** Historique des paiements reçus (trace comptable de la plateforme). */
    @GetMapping("/paiements")
    public String paiements(Model model) {
        List<Paiement> paiements = paiementRepository.findAllByOrderByDatePaiementDesc();
        Map<Long, String> nomParEtab = etablissementRepository.findAll().stream()
                .collect(Collectors.toMap(Etablissement::getId, Etablissement::getNom));
        LocalDate now = LocalDate.now();
        model.addAttribute("paiements", paiements);
        model.addAttribute("nomParEtab", nomParEtab);
        model.addAttribute("totalEncaisse", paiementRepository.totalEncaisse());
        model.addAttribute("encaisseMois", paiementRepository.encaisseDepuis(now.withDayOfMonth(1).atStartOfDay()));
        model.addAttribute("nbPaiements", paiements.size());
        return "plateforme/paiements";
    }

    /**
     * Prolongation manuelle après paiement constaté (Mobile Money) : enregistre un Paiement
     * (montant reçu, mois crédités, référence) ET prolonge la période de l'établissement.
     */
    @PostMapping("/abonnements/prolonger")
    @Transactional
    public String prolonger(@AuthenticationPrincipal User superAdmin,
                            @RequestParam String slug,
                            @RequestParam(defaultValue = "1") int mois,
                            @RequestParam(required = false) Long montant,
                            @RequestParam(required = false) String reference,
                            RedirectAttributes ra) {
        etablissementRepository.findBySlug(slug).ifPresentOrElse(e -> {
            int m = Math.max(1, mois);
            long paye = (montant != null && montant >= 0) ? montant : (long) m * planService.prixMensuel(e.getPlan());

            paiementRepository.save(Paiement.builder()
                    .etablissementId(e.getId())
                    .montant(paye)
                    .moisCredites(m)
                    .reference(reference == null || reference.isBlank() ? null : reference.trim())
                    .enregistrePar(superAdmin != null ? superAdmin.getEmail() : null)
                    .build());

            abonnementService.prolonger(e, m);
            etablissementRepository.save(e);
            ra.addFlashAttribute("ok", "Paiement de « " + e.getNom() + " » enregistré ("
                    + paye + " FCFA) — abonnement prolongé de " + m + " mois (jusqu'au "
                    + e.getDateExpiration() + ").");
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
