package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.service.JetonEcranSalle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ci.esatic.sigep.service.ReseauEcran;
import java.util.List;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Écran d'émargement (QR de salle) — accès depuis l'admin SANS jamais exposer la clé kiosque
 * dans le HTML des pages (C4 durci, cf. audit).
 *
 * <ul>
 *   <li>{@code GET /admin/ecran-emargement/ouvrir} : pose sur l'appareil un cookie signé à partir
 *       de la clé kiosque, puis redirige (302) vers la page QR. La clé n'apparaît ni dans le DOM
 *       des pages admin, ni dans l'adresse de l'écran. Cible du bouton « Écran d'émargement ».</li>
 *   <li>{@code GET /admin/ecran-emargement} : petite page de gestion (ouvrir + régénérer la clé).</li>
 *   <li>{@code POST /admin/ecran-emargement/regenerer-cle} : révoque la clé fuitée en en générant
 *       une nouvelle (rend la capacité réellement révocable depuis l'admin).</li>
 * </ul>
 *
 * Réservé à ROLE_ADMIN (chaîne web /admin/**). La clé est toujours celle du tenant courant.
 */
@Controller
@RequiredArgsConstructor
public class EcranEmargementController {

    private final EtablissementCourantService etablissementCourantService;
    private final EtablissementRepository etablissementRepository;
    private final JetonEcranSalle jetonEcranSalle;
    private final ci.esatic.sigep.security.ClientIpResolver clientIpResolver;

    @GetMapping("/admin/ecran-emargement")
    public String page(Model model, HttpServletRequest request) {
        Etablissement e = etablissementCourantService.courant();
        model.addAttribute("ecranPret", e != null && e.getSlug() != null);
        if (!model.containsAttribute("reseaux")) {
            model.addAttribute("reseaux", e == null ? "" : e.getEcranReseaux());
        }
        model.addAttribute("ipActuelle", clientIpResolver.resolve(request));
        return "admin/ecran-emargement";
    }

    /** Réseau d'où l'écran peut s'afficher. Vide : partout où son cookie l'autorise. */
    @PostMapping("/admin/ecran-emargement/reseau")
    public RedirectView enregistrerReseau(@RequestParam(required = false) String reseaux,
                                          RedirectAttributes flash) {
        Etablissement e = etablissementCourantService.courant();
        if (e == null) return new RedirectView("/admin/ecran-emargement?indispo=1");
        String saisie = reseaux == null ? "" : reseaux.trim();
        try {
            List<String> entrees = ReseauEcran.lire(saisie);
            String propre = String.join(", ", entrees);
            if (propre.length() > ReseauEcran.LONGUEUR_MAX) throw new IllegalArgumentException("liste trop longue");
            e.setEcranReseaux(propre.isEmpty() ? null : propre);
            etablissementRepository.save(e);
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("reseauInvalide", ex.getMessage());
            flash.addFlashAttribute("reseaux", saisie.length() > ReseauEcran.LONGUEUR_MAX
                    ? saisie.substring(0, ReseauEcran.LONGUEUR_MAX) : saisie);
            return new RedirectView("/admin/ecran-emargement");
        }
        return new RedirectView("/admin/ecran-emargement?reseau=1");
    }

    /**
     * Autorise cet appareil par un cookie, puis ouvre la page QR du tenant. La clé n'apparaît ni
     * dans les pages admin, ni dans l'adresse de l'écran, ni dans son historique.
     */
    @GetMapping("/admin/ecran-emargement/ouvrir")
    public RedirectView ouvrir(HttpServletRequest request, HttpServletResponse response) {
        Etablissement e = assurerCle();
        if (e == null) {
            return new RedirectView("/admin/ecran-emargement?indispo=1");
        }
        response.addHeader(HttpHeaders.SET_COOKIE, jetonEcranSalle.cookie(e, request.isSecure()).toString());
        return new RedirectView("/api/qr/display?etab=" + enc(e.getSlug()));
    }

    /** Régénère la clé kiosque (révocation d'une clé potentiellement fuitée). */
    @PostMapping("/admin/ecran-emargement/regenerer-cle")
    public RedirectView regenererCle() {
        Etablissement e = etablissementCourantService.courant();
        if (e != null) {
            e.setKioskKey(genererKioskKey());
            etablissementRepository.save(e);
        }
        return new RedirectView("/admin/ecran-emargement?regenere=1");
    }

    /** Garantit une clé kiosque pour le tenant courant (génère+persiste si absente). */
    private Etablissement assurerCle() {
        Etablissement e = etablissementCourantService.courant();
        if (e == null || e.getSlug() == null) return null;
        if (e.getKioskKey() == null || e.getKioskKey().isBlank()) {
            e.setKioskKey(genererKioskKey());
            e = etablissementRepository.save(e);
        }
        return e;
    }

    private String genererKioskKey() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
