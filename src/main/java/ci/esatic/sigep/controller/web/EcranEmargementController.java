package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.service.EtablissementCourantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Écran d'émargement (QR de salle) — accès depuis l'admin SANS jamais exposer la clé kiosque
 * dans le HTML des pages (C4 durci, cf. audit).
 *
 * <ul>
 *   <li>{@code GET /admin/ecran-emargement/ouvrir} : résout la clé kiosque du tenant CÔTÉ SERVEUR
 *       et redirige (302) vers la page QR publique. La clé n'apparaît que dans l'onglet cible,
 *       jamais dans le DOM des pages admin. C'est la cible du bouton « Écran d'émargement ».</li>
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

    @GetMapping("/admin/ecran-emargement")
    public String page(Model model) {
        Etablissement e = etablissementCourantService.courant();
        model.addAttribute("ecranPret", e != null && e.getSlug() != null);
        return "admin/ecran-emargement";
    }

    /** Redirige vers la page QR du tenant, clé injectée côté serveur (jamais dans le DOM admin). */
    @GetMapping("/admin/ecran-emargement/ouvrir")
    public RedirectView ouvrir() {
        Etablissement e = assurerCle();
        if (e == null) {
            return new RedirectView("/admin/ecran-emargement?indispo=1");
        }
        String url = "/api/qr/display?etab=" + enc(e.getSlug()) + "&key=" + enc(e.getKioskKey());
        return new RedirectView(url);
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
