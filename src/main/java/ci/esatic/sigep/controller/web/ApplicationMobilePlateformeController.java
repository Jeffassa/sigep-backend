package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.ApplicationMobile;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.service.ApplicationMobileService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.util.Map;

/**
 * Espace plateforme : déposer l'APK de l'application Android, que les établissements envoient
 * ensuite à leurs enseignants.
 *
 * <p>Le fichier arrive en corps brut (application/octet-stream) et non en formulaire multipart :
 * la limite des formulaires reste basse pour tout le site, et le fichier est rangé au fil de la
 * lecture, sans être gardé entier en mémoire.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequestMapping("/plateforme/application")
@Slf4j
public class ApplicationMobilePlateformeController {

    private final ApplicationMobileService service;

    @GetMapping
    public String page(Model model) {
        ApplicationMobile app = service.courante().filter(a -> a.getMorceaux() > 0).orElse(null);
        model.addAttribute("apk", app);
        model.addAttribute("tailleMo", app == null ? 0 : Math.max(1, Math.round(app.getTaille() / 1048576.0)));
        model.addAttribute("cheminTelechargement", ApplicationMobileController.CHEMIN);
        model.addAttribute("tailleMaxMo", ApplicationMobileService.TAILLE_MAX / (1024 * 1024));
        return "plateforme/application";
    }

    @PostMapping("/depot")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deposer(@RequestParam String version, HttpServletRequest request,
                                                       @AuthenticationPrincipal User auteur) {
        try {
            ApplicationMobile app = service.deposer(request.getInputStream(), version,
                    auteur == null ? null : auteur.getEmail());
            log.info("APK {} déposé ({} octets, sha256 {})", app.getVersion(), app.getTaille(), app.getSha256());
            return ResponseEntity.ok(Map.of("ok", true,
                    "message", "SIGEP " + app.getVersion() + " est en ligne : les établissements peuvent l'envoyer."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        } catch (IOException e) {
            log.warn("Dépôt d'APK interrompu : {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "message", "Envoi interrompu : réessayez."));
        }
    }
}
