package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.service.AbonnementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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
    private final AbonnementService abonnementService;

    /** Vue d'ensemble : KPIs de la plateforme + liste des établissements clients. */
    @GetMapping({"", "/"})
    public String dashboard(Model model) {
        List<Etablissement> etablissements = clients();

        Map<Long, Long> enseignantsParEtab = new HashMap<>();
        for (Object[] ligne : enseignantRepository.countParEtablissement()) {
            enseignantsParEtab.put((Long) ligne[0], (Long) ligne[1]);
        }

        Set<Long> expires = etablissements.stream()
                .filter(abonnementService::estExpire)
                .map(Etablissement::getId)
                .collect(Collectors.toSet());
        Map<String, Long> parPlan = etablissements.stream()
                .collect(Collectors.groupingBy(e -> e.getPlan().name(), Collectors.counting()));

        model.addAttribute("etablissements", etablissements);
        model.addAttribute("enseignantsParEtab", enseignantsParEtab);
        model.addAttribute("expires", expires);
        model.addAttribute("parPlan", parPlan);
        model.addAttribute("nbEtablissements", etablissements.size());
        model.addAttribute("nbActifs", etablissements.stream().filter(Etablissement::isActif).count());
        model.addAttribute("nbExpires", (long) expires.size());
        return "plateforme/dashboard";
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
