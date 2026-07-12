package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.service.AbonnementService;
import ci.esatic.sigep.service.EtablissementCourantService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.LocalDate;

/**
 * Expose à TOUTES les pages admin (Thymeleaf) le contexte du tenant connecté :
 * établissement, plan, identité, et état d'abonnement (expiré, jours restants).
 * Valeurs nulles hors session admin (pages publiques) → l'UI a un repli.
 */
@ControllerAdvice(basePackages = "ci.esatic.sigep.controller.web")
@RequiredArgsConstructor
public class TenantWebModelAdvice {

    private final AbonnementService abonnementService;
    private final EtablissementCourantService etablissementCourantService;

    /** Email du propriétaire de la plateforme (peut valider les renouvellements manuels). */
    @Value("${app.platform.owner-email:assalendahjeanfrancois@gmail.com}")
    private String ownerEmail;

    @ModelAttribute("etablissementNom")
    public String etablissementNom() {
        Etablissement e = etablissementCourant();
        return e != null ? e.getNom() : null;
    }

    @ModelAttribute("etablissementPlan")
    public String etablissementPlan() {
        Etablissement e = etablissementCourant();
        return (e != null && e.getPlan() != null) ? e.getPlan().name() : null;
    }

    @ModelAttribute("adminEmail")
    public String adminEmail() {
        User u = utilisateurCourant();
        return u != null ? u.getEmail() : null;
    }

    @ModelAttribute("abonnementExpire")
    public boolean abonnementExpire() {
        return abonnementService.estExpire(etablissementCourant());
    }

    @ModelAttribute("abonnementRappel")
    public boolean abonnementRappel() {
        return abonnementService.doitRappeler(etablissementCourant());
    }

    @ModelAttribute("joursAvantExpiration")
    public Long joursAvantExpiration() {
        return abonnementService.joursAvantExpiration(etablissementCourant());
    }

    @ModelAttribute("dateExpiration")
    public LocalDate dateExpiration() {
        Etablissement e = etablissementCourant();
        return e != null ? e.getDateExpiration() : null;
    }

    @ModelAttribute("estProprietairePlateforme")
    public boolean estProprietairePlateforme() {
        User u = utilisateurCourant();
        return u != null && ownerEmail != null && ownerEmail.equalsIgnoreCase(u.getEmail());
    }

    private Etablissement etablissementCourant() {
        // Relu en base : plan, expiration et rappels reflètent un paiement récent sans reconnexion.
        return etablissementCourantService.courant();
    }

    private User utilisateurCourant() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return (a != null && a.getPrincipal() instanceof User u) ? u : null;
    }
}
