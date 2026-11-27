package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.service.assistant.AssistantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * Assistant de la console : la fenêtre de discussion, son activation par l'établissement, et la
 * confirmation des actions qu'il propose. Réservé à l'administrateur principal (règle par défaut
 * de la chaîne /admin/**).
 */
@Controller
@RequiredArgsConstructor
public class AssistantWebController {

    private final AssistantService assistantService;

    @GetMapping("/admin/assistant")
    public String page(@AuthenticationPrincipal User admin, HttpSession session, Model model) {
        model.addAttribute("etat", assistantService.etat(session.getId(), admin));
        return "admin/assistant";
    }

    @PostMapping("/admin/assistant/activer")
    public String activer(@AuthenticationPrincipal User admin, @RequestParam(defaultValue = "false") boolean accord,
                          RedirectAttributes ra) {
        if (!accord) {
            ra.addFlashAttribute("error", "Cochez la case pour confirmer que vous avez lu ce que l'assistant envoie, et où.");
            return "redirect:/admin/assistant";
        }
        try {
            assistantService.activer(admin);
            ra.addFlashAttribute("success", "Assistant activé pour votre établissement.");
        } catch (MetierException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/assistant";
    }

    @PostMapping("/admin/assistant/desactiver")
    public String desactiver(@AuthenticationPrincipal User admin, RedirectAttributes ra) {
        assistantService.desactiver(admin);
        ra.addFlashAttribute("success", "Assistant désactivé : plus rien n'est envoyé au fournisseur d'IA.");
        return "redirect:/admin/assistant";
    }

    public record Demande(String texte) { }

    @PostMapping("/admin/assistant/message")
    @ResponseBody
    public ResponseEntity<?> message(@AuthenticationPrincipal User admin, @RequestBody Demande demande,
                                     HttpSession session, HttpServletRequest requete) {
        try {
            return ResponseEntity.ok(assistantService.envoyer(session.getId(), admin,
                    demande == null ? null : demande.texte(), requete));
        } catch (MetierException e) {
            return ResponseEntity.badRequest().body(Map.of("erreur", e.getMessage(), "code", e.getCode()));
        }
    }

    @PostMapping("/admin/assistant/actions/{id}/confirmer")
    @ResponseBody
    public ResponseEntity<?> confirmer(@AuthenticationPrincipal User admin, @PathVariable String id,
                                       HttpSession session, HttpServletRequest requete) {
        try {
            return ResponseEntity.ok(Map.of("message", assistantService.confirmer(session.getId(), admin, id, requete)));
        } catch (MetierException e) {
            return ResponseEntity.badRequest().body(Map.of("erreur", e.getMessage(), "code", e.getCode()));
        }
    }

    @PostMapping("/admin/assistant/actions/{id}/annuler")
    @ResponseBody
    public Map<String, Object> annuler(@AuthenticationPrincipal User admin, @PathVariable String id, HttpSession session) {
        assistantService.annuler(session.getId(), admin, id);
        return Map.of("ok", true);
    }

    @PostMapping("/admin/assistant/nouvelle")
    public String nouvelle(@AuthenticationPrincipal User admin, HttpSession session) {
        assistantService.nouvelle(session.getId(), admin);
        return "redirect:/admin/assistant";
    }
}
