package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
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

import java.util.Set;
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

    /** Au-delà, l'image n'est plus un logo : on refuse plutôt que de stocker n'importe quoi. */
    private static final long TAILLE_MAX = 512 * 1024;

    /**
     * Formats admis. Le SVG en est écarté à dessein : c'est un document XML, qui peut porter
     * du script exécuté par le navigateur si l'image est servie sur le domaine du site.
     */
    private static final Set<String> TYPES_ADMIS =
            Set.of("image/png", "image/jpeg", "image/webp");

    private final EtablissementRepository etablissementRepository;
    private final EtablissementCourantService etablissementCourantService;
    private final PlanService planService;

    @PostMapping("/admin/etablissement/logo")
    @Transactional
    public String deposer(@RequestParam("fichier") MultipartFile fichier, RedirectAttributes ra) {
        Etablissement etablissement = etablissementCourantService.courant();
        if (etablissement == null) {
            ra.addFlashAttribute("error", "Aucun établissement rattaché à votre compte.");
            return "redirect:/admin/referentiels";
        }
        // Lève une exception rendue en 403 si le plan ne comprend pas la marque.
        planService.exiger(etablissement, Feature.BRANDING);

        if (fichier == null || fichier.isEmpty()) {
            ra.addFlashAttribute("error", "Choisissez une image avant d'envoyer.");
            return "redirect:/admin/referentiels";
        }
        if (fichier.getSize() > TAILLE_MAX) {
            ra.addFlashAttribute("error", "Image trop lourde : "
                    + (fichier.getSize() / 1024) + " Ko pour un maximum de " + (TAILLE_MAX / 1024)
                    + " Ko. Réduisez-la avant de réessayer.");
            return "redirect:/admin/referentiels";
        }
        String type = fichier.getContentType();
        if (type == null || !TYPES_ADMIS.contains(type.toLowerCase())) {
            ra.addFlashAttribute("error",
                    "Format non accepté. Envoyez une image PNG, JPEG ou WebP.");
            return "redirect:/admin/referentiels";
        }

        try {
            etablissement.setLogoDonnees(fichier.getBytes());
            etablissement.setLogoType(type.toLowerCase());
            etablissementRepository.save(etablissement);
            ra.addFlashAttribute("success", "Logo mis à jour.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "L'image n'a pas pu être lue. Réessayez.");
        }
        return "redirect:/admin/referentiels";
    }

    @PostMapping("/admin/etablissement/logo/retirer")
    @Transactional
    public String retirer(RedirectAttributes ra) {
        Etablissement etablissement = etablissementCourantService.courant();
        if (etablissement == null) {
            return "redirect:/admin/referentiels";
        }
        planService.exiger(etablissement, Feature.BRANDING);

        etablissement.setLogoDonnees(null);
        etablissement.setLogoType(null);
        etablissementRepository.save(etablissement);
        ra.addFlashAttribute("success", "Logo retiré. Le logo SIGEP reprend sa place.");
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
