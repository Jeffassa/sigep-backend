package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.security.sso.SsoIndisponibleException;
import ci.esatic.sigep.service.ConfigurationSsoService;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * L'écran par lequel un établissement branche son annuaire sur SIGEP.
 *
 * <p>Il affiche aussi l'URL de retour à déclarer chez le fournisseur d'identité. Ce détail décide
 * de la réussite ou de l'échec : une URL de retour qui ne correspond pas au caractère près est le
 * motif de refus numéro un d'une première configuration OIDC, et le message du fournisseur
 * (« redirect_uri_mismatch ») ne dit jamais quelle valeur il attendait. Elle est donc écrite ici,
 * complète, prête à être copiée.
 *
 * <p>Le verrou d'offre est posé à l'entrée ET à l'enregistrement, mais ce n'est pas lui qui
 * protège : celui qui compte est posé sur le chemin de connexion lui-même, dans
 * {@code RegistreSsoTenant}. Un plan qui perd le SSO ferme la porte le jour même, que cet écran
 * soit encore atteignable ou non.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class SsoAdminWebController {

    private final ConfigurationSsoService service;
    private final EtablissementCourantService etablissementCourantService;
    private final PlanService planService;

    @GetMapping("/admin/sso")
    public String page(Model model, jakarta.servlet.http.HttpServletRequest requete) {
        Etablissement etablissement = etablissementCourantService.courant();
        model.addAttribute("ssoDisponible", planService.estDisponible(etablissement, Feature.SSO));
        model.addAttribute("ssoInstalle", service.disponibleSurCeServeur());
        model.addAttribute("config", service.charger(etablissement).orElse(null));
        model.addAttribute("secretRelisible", service.secretRelisible(etablissement));
        model.addAttribute("slug", etablissement == null ? "" : etablissement.getSlug());
        model.addAttribute("urlRetour", baseUrl(requete) + "/login/oauth2/code/"
                + (etablissement == null ? "" : etablissement.getSlug()));
        model.addAttribute("urlConnexion", baseUrl(requete) + "/sso/"
                + (etablissement == null ? "" : etablissement.getSlug()));
        return "admin/sso";
    }

    @PostMapping("/admin/sso")
    public String enregistrer(@RequestParam(required = false) String fournisseur,
                              @RequestParam(required = false) String issuer,
                              @RequestParam(required = false) String clientId,
                              @RequestParam(required = false) String clientSecret,
                              @RequestParam(required = false) String domaines,
                              @RequestParam(required = false, defaultValue = "false") boolean actif,
                              @RequestParam(required = false, defaultValue = "false") boolean exigerOtp,
                              RedirectAttributes ra) {
        Etablissement etablissement = etablissementCourantService.courant();
        exigerLOption(etablissement);
        try {
            service.enregistrer(etablissement, fournisseur, issuer, clientId, clientSecret,
                    domaines, actif, exigerOtp);
            ra.addFlashAttribute("success", actif
                    ? "Configuration enregistrée. Essayez la connexion depuis une autre fenêtre "
                      + "AVANT de fermer celle-ci : vous gardez ainsi votre mot de passe sous la main."
                    : "Configuration enregistrée. Le SSO reste désactivé tant que vous n'aurez pas "
                      + "coché « Activer ».");
        } catch (SsoIndisponibleException e) {
            // Le message vient de notre validation, jamais du fournisseur : il ne contient ni
            // secret, ni détail d'installation.
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/sso";
    }

    @PostMapping("/admin/sso/supprimer")
    public String supprimer(RedirectAttributes ra) {
        Etablissement etablissement = etablissementCourantService.courant();
        exigerLOption(etablissement);
        if (service.supprimer(etablissement)) {
            ra.addFlashAttribute("success",
                    "Configuration supprimée. Les connexions se font de nouveau par mot de passe — "
                    + "aucun compte n'a été touché.");
        } else {
            ra.addFlashAttribute("error", "Aucune configuration à supprimer.");
        }
        return "redirect:/admin/sso";
    }

    /**
     * Refuse l'écriture quand l'offre ne comprend pas le SSO.
     *
     * <p>Un vrai 403, et non l'exception de {@code PlanService} : celle-ci est traduite en JSON
     * par le gestionnaire des API REST, qui ne s'applique pas aux contrôleurs web. Laissée
     * telle quelle, elle produirait ici une page d'erreur 500 — où le visiteur lirait « erreur
     * du serveur » pour ce qui est une décision commerciale parfaitement normale.
     */
    private void exigerLOption(Etablissement etablissement) {
        if (!planService.estDisponible(etablissement, Feature.SSO)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "La connexion par votre annuaire fait partie du plan Enterprise.");
        }
    }

    /**
     * Adresse publique du serveur, telle que le fournisseur d'identité devra la connaître.
     *
     * <p>Reconstituée à partir de l'en-tête {@code Host} de la requête en cours, ce qui donne la
     * bonne valeur en production comme en développement sans rien à configurer. Derrière un
     * mandataire, {@code X-Forwarded-Proto} rétablit {@code https} — sans quoi l'adresse affichée
     * commencerait par {@code http} et serait refusée par la plupart des fournisseurs.
     */
    private static String baseUrl(jakarta.servlet.http.HttpServletRequest r) {
        String protocole = r.getHeader("X-Forwarded-Proto");
        if (protocole == null || protocole.isBlank()) protocole = r.getScheme();
        String hote = r.getHeader("X-Forwarded-Host");
        if (hote == null || hote.isBlank()) {
            hote = r.getServerName();
            int port = r.getServerPort();
            boolean portParDefaut = ("https".equals(protocole) && port == 443)
                    || ("http".equals(protocole) && port == 80);
            if (!portParDefaut) hote = hote + ":" + port;
        }
        // Un en-tête transmis peut contenir une liste (« a.ci, b.ci ») : on garde le premier.
        int virgule = hote.indexOf(',');
        if (virgule > 0) hote = hote.substring(0, virgule).trim();
        return protocole + "://" + hote;
    }
}
