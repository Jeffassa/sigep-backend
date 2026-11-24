package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.TypeAnnonce;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.service.AnnonceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

/** Espace plateforme : publier une annonce dans les consoles de tous les établissements. */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequestMapping("/plateforme/annonces")
public class AnnoncesPlateformeController {

    private final AnnonceService annonceService;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("annonces", annonceService.historique());
        model.addAttribute("types", TypeAnnonce.values());
        model.addAttribute("maintenant", LocalDateTime.now());
        model.addAttribute("longueurMax", AnnonceService.LONGUEUR_MAX);
        return "plateforme/annonces";
    }

    @PostMapping
    public String publier(@RequestParam(required = false) TypeAnnonce type,
                          @RequestParam(required = false) String message,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime debut,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fin,
                          @AuthenticationPrincipal User auteur,
                          RedirectAttributes ra) {
        try {
            annonceService.publier(type, message, debut, fin, auteur.getEmail());
            ra.addFlashAttribute("ok", debut != null && debut.isAfter(LocalDateTime.now())
                    ? "Annonce programmée : elle apparaîtra dans les consoles à l'heure prévue."
                    : "Annonce publiée : elle apparaît dans les consoles des établissements.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("erreur", e.getMessage());
            ra.addFlashAttribute("brouillon", message);
        }
        return "redirect:/plateforme/annonces";
    }

    @PostMapping("/{id}/clore")
    public String clore(@PathVariable Long id, RedirectAttributes ra) {
        try {
            annonceService.clore(id);
            ra.addFlashAttribute("ok", "Annonce retirée des consoles.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("erreur", e.getMessage());
        }
        return "redirect:/plateforme/annonces";
    }
}
