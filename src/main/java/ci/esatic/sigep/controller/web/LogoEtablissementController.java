package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.service.LogoService;
import ci.esatic.sigep.tenant.plan.Feature;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.concurrent.TimeUnit;

/**
 * Logo de l'établissement : dépôt, affichage, retrait.
 *
 * <p>La fonctionnalité figurait dans l'offre Pro depuis l'origine, mais rien ne permettait de
 * déposer une image : la colonne prévue pour elle n'était renseignée nulle part. Un
 * établissement qui payait pour « le logo de l'établissement » ne recevait donc rien.
 *
 * <p>C'est aussi le premier endroit où {@link Feature#BRANDING} est réellement exigée. La
 * fonctionnalité était déclarée dans la table des plans sans qu'aucun code ne la contrôle :
 * elle distinguait les offres sur le papier, pas dans les faits.
 */
@Controller
@RequiredArgsConstructor
public class LogoEtablissementController {

    private final EtablissementCourantService etablissementCourantService;
    private final LogoService logoService;

    @PostMapping("/admin/etablissement/logo")
    public String deposer(@RequestParam("fichier") MultipartFile fichier, RedirectAttributes ra) {
        try {
            logoService.deposer(fichier == null ? null : fichier.getBytes(), fichier == null ? null : fichier.getContentType());
            ra.addFlashAttribute("success", "Logo mis à jour.");
        } catch (MetierException e) {
            ra.addFlashAttribute("error", e.getMessage());
        } catch (java.io.IOException e) {
            ra.addFlashAttribute("error", "L'image n'a pas pu être lue. Réessayez.");
        }
        return "redirect:/admin/referentiels";
    }

    @PostMapping("/admin/etablissement/logo/retirer")
    public String retirer(RedirectAttributes ra) {
        try {
            logoService.retirer();
            ra.addFlashAttribute("success", "Logo retiré. Le logo SIGEP reprend sa place.");
        } catch (MetierException e) {
            // Aucun établissement rattaché : rien à retirer.
        }
        return "redirect:/admin/referentiels";
    }

    /**
     * Sert l'image à l'établissement connecté.
     *
     * <p>La route vit sous {@code /admin} : elle passe donc par la chaîne de sécurité web, et
     * l'image n'est lisible que par les comptes de l'établissement auquel elle appartient. Un
     * logo n'a rien de secret, mais rien n'oblige à l'exposer publiquement.
     */
    @GetMapping("/admin/etablissement/logo")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> afficher() {
        Etablissement etablissement = etablissementCourantService.courant();
        if (etablissement == null || etablissement.getLogoDonnees() == null
                || etablissement.getLogoDonnees().length == 0) {
            return ResponseEntity.notFound().build();
        }
        MediaType type;
        try {
            type = MediaType.parseMediaType(etablissement.getLogoType());
        } catch (Exception e) {
            type = MediaType.IMAGE_PNG;
        }
        return ResponseEntity.ok()
                .contentType(type)
                // Privé : l'image dépend de l'établissement connecté, un intermédiaire ne doit
                // pas la servir à quelqu'un d'autre. Courte durée pour qu'un changement de logo
                // se voie sans attendre.
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
                .body(etablissement.getLogoDonnees());
    }
}
