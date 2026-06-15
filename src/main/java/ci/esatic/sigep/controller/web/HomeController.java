package ci.esatic.sigep.controller.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Redirige la racine du site vers l'interface d'administration.
 * Un utilisateur non authentifié sera alors dirigé vers la page de connexion
 * personnalisée (/admin-login) par la chaîne de sécurité admin.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "redirect:/admin/dashboard";
    }
}
