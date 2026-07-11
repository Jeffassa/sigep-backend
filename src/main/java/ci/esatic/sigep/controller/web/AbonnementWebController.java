package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.service.StripeService;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Page d'abonnement d'un établissement (statut + paiement). Deux voies :
 *  - MANUELLE : Mobile Money hors-ligne, validée ensuite par le super admin ;
 *  - EN LIGNE : Stripe Checkout (carte) — le webhook enregistre le paiement et prolonge.
 * Reste accessible même quand l'abonnement est expiré.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AbonnementWebController {

    @Value("${app.platform.owner-email:assalendahjeanfrancois@gmail.com}")
    private String ownerEmail;

    private final StripeService stripeService;
    private final PlanService planService;

    @GetMapping("/admin/abonnement")
    public String abonnement(Model model) {
        model.addAttribute("ownerEmail", ownerEmail);
        model.addAttribute("stripeEnabled", stripeService.isEnabled());
        model.addAttribute("prixPro", planService.prixMensuel(Plan.PRO));
        return "admin/abonnement";
    }

    /** Paiement en ligne : crée une session Stripe Checkout et redirige vers la page de paiement. */
    @PostMapping("/admin/abonnement/payer")
    public String payer(@AuthenticationPrincipal User user,
                        @RequestParam(defaultValue = "1") int mois,
                        RedirectAttributes ra) {
        if (!stripeService.isEnabled()) {
            ra.addFlashAttribute("erreurAbo", "Le paiement en ligne n'est pas activé pour le moment.");
            return "redirect:/admin/abonnement";
        }
        Etablissement e = user == null ? null : user.getEtablissement();
        if (e == null) {
            ra.addFlashAttribute("erreurAbo", "Compte sans établissement.");
            return "redirect:/admin/abonnement";
        }
        int m = Math.max(1, mois);
        long montant = (long) m * planService.prixMensuel(Plan.PRO);
        try {
            return "redirect:" + stripeService.creerSessionCheckout(e, m, montant, user.getEmail());
        } catch (Exception ex) {
            log.error("Création session Stripe échouée : {}", ex.getMessage());
            ra.addFlashAttribute("erreurAbo", "Paiement en ligne indisponible. Réessayez plus tard.");
            return "redirect:/admin/abonnement";
        }
    }
}
