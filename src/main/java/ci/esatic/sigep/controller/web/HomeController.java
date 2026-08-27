package ci.esatic.sigep.controller.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Pages publiques (SaaS) : accueil marketing + inscription d'un établissement.
 * L'espace d'administration reste sur /admin (connexion via /admin-login).
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String accueil() {
        return "public/landing";
    }

    @GetMapping("/inscription")
    public String inscription() {
        return "public/inscription";
    }

    // Pages légales (conformité B2B / protection des données personnelles).
    @GetMapping("/mentions-legales")
    public String mentionsLegales() {
        return "public/mentions-legales";
    }

    @GetMapping("/confidentialite")
    public String confidentialite() {
        return "public/confidentialite";
    }

    @GetMapping("/cgu")
    public String cgu() {
        return "public/cgu";
    }
}
