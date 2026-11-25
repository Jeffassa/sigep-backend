package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.CleAgentRepository;
import ci.esatic.sigep.repository.JournalAgentRepository;
import ci.esatic.sigep.service.CleAgentService;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Clés d'agent et journal de l'agent, côté console.
 *
 * <p>Réservé à l'administrateur principal (règle par défaut de la chaîne admin). Une clé est
 * toujours émise au nom de celui qui la crée : l'agent agira avec SES droits.
 */
@Controller
@RequiredArgsConstructor
public class CleAgentWebController {

    private final CleAgentRepository cleAgentRepository;
    private final JournalAgentRepository journalAgentRepository;
    private final CleAgentService cleAgentService;
    private final PlanService planService;
    private final EtablissementCourantService etablissementCourantService;

    @GetMapping("/admin/agent")
    public String page(Model model) {
        model.addAttribute("cles", cleAgentRepository.findAllByOrderByCreeeLeDesc());
        model.addAttribute("journal", journalAgentRepository.findTop50ByOrderByDateHeureDesc());
        model.addAttribute("agentDisponible", planService.estDisponible(
                etablissementCourantService.courant(), Feature.AGENT));
        return "admin/agent";
    }

    @PostMapping("/admin/agent/cles")
    public String emettre(@AuthenticationPrincipal User admin,
                          @RequestParam(required = false) String libelle, RedirectAttributes ra) {
        planService.exiger(etablissementCourantService.courant(), Feature.AGENT);
        // Transmise par flash : affichée une seule fois, elle n'est conservée nulle part en clair.
        ra.addFlashAttribute("cleEnClair", cleAgentService.emettre(admin, libelle));
        ra.addFlashAttribute("success", "Clé créée. Copiez-la maintenant : elle ne sera plus affichée.");
        return "redirect:/admin/agent";
    }

    @PostMapping("/admin/agent/cles/{id}/revoquer")
    public String revoquer(@PathVariable Long id, RedirectAttributes ra) {
        // Pas de contrôle de plan ici : un établissement redescendu au plan gratuit doit pouvoir
        // couper un agent dont il ne veut plus, même s'il ne peut plus en créer.
        if (cleAgentService.revoquer(id)) {
            ra.addFlashAttribute("success", "Clé révoquée. L'agent qui l'utilise ne peut plus rien faire.");
        } else {
            ra.addFlashAttribute("error", "Clé introuvable.");
        }
        return "redirect:/admin/agent";
    }
}
