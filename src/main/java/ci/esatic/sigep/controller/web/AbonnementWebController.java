package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.service.AbonnementService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Page d'abonnement (statut, renouvellement). Renouvellement MANUEL pour l'instant :
 * l'établissement paie hors-ligne (Mobile Money / virement) puis le propriétaire de la
 * plateforme prolonge la période. Reste accessible même quand l'abonnement est expiré.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AbonnementWebController {

    private final EtablissementRepository etablissementRepository;
    private final AbonnementService abonnementService;

    @Value("${app.platform.owner-email:assalendahjeanfrancois@gmail.com}")
    private String ownerEmail;

    @GetMapping("/admin/abonnement")
    public String abonnement(@AuthenticationPrincipal User user, Model model) {
        model.addAttribute("ownerEmail", ownerEmail);
        // Le propriétaire de la plateforme voit tous les établissements pour valider les renouvellements.
        if (user != null && ownerEmail.equalsIgnoreCase(user.getEmail())) {
            model.addAttribute("tousEtablissements", etablissementRepository.findAll());
        }
        return "admin/abonnement";
    }

    /** Prolongation manuelle (propriétaire plateforme uniquement) après paiement constaté. */
    @PostMapping("/admin/abonnement/prolonger")
    @Transactional
    public String prolonger(@AuthenticationPrincipal User user,
                            @RequestParam String slug,
                            @RequestParam(defaultValue = "1") int mois,
                            RedirectAttributes ra) {
        if (user == null || !ownerEmail.equalsIgnoreCase(user.getEmail())) {
            ra.addFlashAttribute("erreurAbo", "Action réservée au propriétaire de la plateforme.");
            return "redirect:/admin/abonnement";
        }
        etablissementRepository.findBySlug(slug).ifPresentOrElse(e -> {
            abonnementService.prolonger(e, Math.max(1, mois));
            etablissementRepository.save(e);
            ra.addFlashAttribute("okAbo", "Abonnement de « " + e.getNom() + " » prolongé de "
                    + Math.max(1, mois) + " mois (jusqu'au " + e.getDateExpiration() + ").");
        }, () -> ra.addFlashAttribute("erreurAbo", "Établissement introuvable : " + slug));
        return "redirect:/admin/abonnement";
    }
}
