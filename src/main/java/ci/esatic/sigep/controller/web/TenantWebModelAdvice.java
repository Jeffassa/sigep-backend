package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Expose l'établissement (tenant) et le plan de l'admin connecté à TOUTES les pages
 * admin (Thymeleaf), pour personnaliser l'en-tête : nom de l'établissement, plan,
 * identité. Valeurs nulles hors session admin (pages publiques) → l'UI a un repli.
 */
@ControllerAdvice(basePackages = "ci.esatic.sigep.controller.web")
public class TenantWebModelAdvice {

    @ModelAttribute("etablissementNom")
    public String etablissementNom() {
        User u = utilisateurCourant();
        return (u != null && u.getEtablissement() != null) ? u.getEtablissement().getNom() : null;
    }

    @ModelAttribute("etablissementPlan")
    public String etablissementPlan() {
        User u = utilisateurCourant();
        return (u != null && u.getEtablissement() != null && u.getEtablissement().getPlan() != null)
                ? u.getEtablissement().getPlan().name() : null;
    }

    @ModelAttribute("adminEmail")
    public String adminEmail() {
        User u = utilisateurCourant();
        return u != null ? u.getEmail() : null;
    }

    private User utilisateurCourant() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return (a != null && a.getPrincipal() instanceof User u) ? u : null;
    }
}
