package ci.esatic.sigep.controller.web;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Page d'abonnement d'un établissement (statut, instructions de renouvellement).
 * Renouvellement MANUEL pour l'instant : l'établissement paie hors-ligne (Mobile Money)
 * puis le SUPER ADMIN valide et prolonge depuis son espace /plateforme/abonnements.
 * Reste accessible même quand l'abonnement est expiré.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AbonnementWebController {

    @Value("${app.platform.owner-email:assalendahjeanfrancois@gmail.com}")
    private String ownerEmail;

    @GetMapping("/admin/abonnement")
    public String abonnement(Model model) {
        model.addAttribute("ownerEmail", ownerEmail);
        return "admin/abonnement";
    }
}
