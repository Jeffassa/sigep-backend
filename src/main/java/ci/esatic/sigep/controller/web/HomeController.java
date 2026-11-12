package ci.esatic.sigep.controller.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Pages publiques (SaaS) : accueil marketing + inscription d'un établissement.
 * L'espace d'administration reste sur /admin (connexion via /admin-login).
 */
@Controller
@lombok.RequiredArgsConstructor
public class HomeController {

    private final ci.esatic.sigep.service.MobileMoneyService mobileMoneyService;
    private final ci.esatic.sigep.tenant.plan.PlanService planService;

    @GetMapping("/")
    public String accueil() {
        return "public/landing";
    }

    /**
     * Inscription. Avec {@code ?plan=PRO} ou {@code ?plan=ENTERPRISE}, venu du bordereau de
     * l'accueil, la page récapitule l'offre et enchaîne sur le paiement quand il est ouvert.
     */
    @GetMapping("/inscription")
    public String inscription(@org.springframework.web.bind.annotation.RequestParam(required = false) String plan,
                              org.springframework.ui.Model model) {
        ci.esatic.sigep.entity.Plan choisi = null;
        if (plan != null) {
            try {
                ci.esatic.sigep.entity.Plan p = ci.esatic.sigep.entity.Plan.valueOf(plan.trim().toUpperCase());
                if (planService.estAchetable(p)) choisi = p;
            } catch (IllegalArgumentException ignore) {
                // plan inconnu : inscription gratuite
            }
        }
        model.addAttribute("planChoisi", choisi == null ? null : choisi.name());
        model.addAttribute("planLibelle", choisi == null ? null
                : choisi == ci.esatic.sigep.entity.Plan.PRO ? "Pro" : "Enterprise");
        model.addAttribute("achatEnLigne", mobileMoneyService.achatEnLigne());
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
