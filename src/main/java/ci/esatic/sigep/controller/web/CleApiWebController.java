package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.repository.CleApiRepository;
import ci.esatic.sigep.service.CleApiService;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Gestion des clés de l'API publique par l'établissement. */
@Controller
@RequiredArgsConstructor
public class CleApiWebController {

    private final CleApiRepository cleApiRepository;
    private final CleApiService cleApiService;
    private final PlanService planService;
    private final EtablissementCourantService etablissementCourantService;

    @GetMapping("/admin/api")
    public String page(Model model) {
        model.addAttribute("cles", cleApiRepository.findAllByOrderByCreeeLeDesc());
        model.addAttribute("apiDisponible", planService.estDisponible(
                etablissementCourantService.courant(), Feature.API_PUBLIQUE));
        return "admin/api";
    }

    @PostMapping("/admin/api/cles")
    public String emettre(@RequestParam(required = false) String libelle, RedirectAttributes ra) {
        planService.exiger(etablissementCourantService.courant(), Feature.API_PUBLIQUE);

        String cle = cleApiService.emettre(libelle);
        // Transmise par flash, donc affichée une seule fois : elle n'existe plus nulle part
        // ensuite, et un rechargement de page ne la fera pas réapparaître.
        ra.addFlashAttribute("cleEnClair", cle);
        ra.addFlashAttribute("success", "Clé créée. Copiez-la maintenant : elle ne sera plus affichée.");
        return "redirect:/admin/api";
    }

    @PostMapping("/admin/api/cles/{id}/revoquer")
    public String revoquer(@PathVariable Long id, RedirectAttributes ra) {
        planService.exiger(etablissementCourantService.courant(), Feature.API_PUBLIQUE);

        if (cleApiService.revoquer(id)) {
            ra.addFlashAttribute("success", "Clé révoquée. Les appels qui l'utilisent échoueront désormais.");
        } else {
            ra.addFlashAttribute("error", "Clé introuvable.");
        }
        return "redirect:/admin/api";
    }
}
