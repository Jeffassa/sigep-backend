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

    /** Email du propriétaire de la plateforme (identité SUPER ADMIN — détection). */
    @Value("${app.platform.owner-email:assalendahjeanfrancois@gmail.com}")
    private String ownerEmail;

    /** Marque affichée dans l'interface (M10) — externalisée, plus de « SIGEP » en dur. */
    @Value("${app.platform.brand-name:SIGEP}")
    private String brandName;

    /** Contact/support public affiché (E12) — jamais d'e-mail personnel en dur dans les templates. */
    @Value("${app.platform.contact-email:contact@sigep.store}")
    private String contactEmail;

    /** Numéro Mobile Money plateforme (repli si l'établissement n'en fixe pas) (E11). */
    @Value("${app.platform.mobile-money:}")
    private String platformMobileMoney;

    /** Domaine Plausible (analytics sans cookie) — vide = désactivé. */
    @Value("${app.analytics.plausible-domain:}")
    private String plausibleDomain;

    @ModelAttribute("plausibleDomain")
    public String plausibleDomain() {
        return (plausibleDomain != null && !plausibleDomain.isBlank()) ? plausibleDomain : null;
    }

    @ModelAttribute("marque")
    public String marque() {
        return brandName;
    }

    @ModelAttribute("contactPlateforme")
    public String contactPlateforme() {
        return contactEmail;
    }

    /** Numéro Mobile Money à afficher : celui du tenant s'il est défini, sinon celui de la plateforme. */
    @ModelAttribute("mobileMoney")
    public String mobileMoney() {
        Etablissement e = etablissementCourant();
        if (e != null && e.getMobileMoneyNumero() != null && !e.getMobileMoneyNumero().isBlank()) {
            return e.getMobileMoneyNumero();
        }
        return platformMobileMoney;
    }

    @ModelAttribute("etablissementNom")
    public String etablissementNom() {
        Etablissement e = etablissementCourant();
        return e != null ? e.getNomEffectif() : null;
    }

    @ModelAttribute("etablissementLogo")
    public String etablissementLogo() {
        Etablissement e = etablissementCourant();
        return e != null ? e.getLogoUrl() : null;
    }

    @ModelAttribute("etablissementCouleur")
    public String etablissementCouleur() {
        Etablissement e = etablissementCourant();
        return (e != null && e.getCouleurPrincipale() != null) ? e.getCouleurPrincipale() : null;
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

    /**
     * Lien direct vers l'écran d'émargement (QR de salle) du tenant, clé kiosque DÉJÀ injectée
     * (C4) : l'admin l'ouvre en un clic, sans jamais saisir de clé. null s'il n'y a pas de tenant
     * (pages publiques) ou tant que la clé kiosque n'existe pas → l'UI masque alors le bouton.
     */
    @ModelAttribute("qrDisplayUrl")
    public String qrDisplayUrl() {
        Etablissement e = etablissementCourant();
        if (e == null || e.getSlug() == null
                || e.getKioskKey() == null || e.getKioskKey().isBlank()) {
            return null;
        }
        return "/api/qr/display?etab=" + encoder(e.getSlug()) + "&key=" + encoder(e.getKioskKey());
    }

    private String encoder(String v) {
        return java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8);
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
