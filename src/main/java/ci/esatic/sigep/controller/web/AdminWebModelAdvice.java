package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.service.AlerteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Attributs de modèle communs aux SEULES vues de l'espace admin (ciblage par type, pour ne pas
 * alourdir les pages publiques/plateforme). Expose le badge d'alertes de la barre supérieure.
 */
@ControllerAdvice(assignableTypes = {
        AdminDashboardController.class,
        AlerteWebController.class,
        ReferentielWebController.class,
        EnseignantWebController.class,
        RapportWebController.class
})
@RequiredArgsConstructor
public class AdminWebModelAdvice {

    private final AlerteService alerteService;

    /** Compteur d'alertes exposé à toutes les vues admin (badge de la barre supérieure). */
    @ModelAttribute("alertesCount")
    public long alertesCount() {
        return alerteService.compter();
    }
}
