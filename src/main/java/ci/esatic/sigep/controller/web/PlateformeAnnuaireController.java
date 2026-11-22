package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.annuaire.AnnuaireCollecte;
import ci.esatic.sigep.annuaire.AnnuaireCollecteRepository;
import ci.esatic.sigep.annuaire.AnnuaireCollecteService;
import ci.esatic.sigep.annuaire.AnnuaireEtablissementRepository;
import ci.esatic.sigep.annuaire.PaysUemoa;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.List;

/** Espace plateforme : l'annuaire des écoles de Côte d'Ivoire et sa collecte ; les autres pays de l'UEMOA en attente. */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequestMapping("/plateforme/annuaire")
public class PlateformeAnnuaireController {

    private final AnnuaireEtablissementRepository etablissements;
    private final AnnuaireCollecteRepository collectes;
    private final AnnuaireCollecteService collecte;

    public record LignePays(PaysUemoa pays, long nombre, AnnuaireCollecte derniere) {
    }

    @GetMapping
    public String annuaire(Model model) {
        List<LignePays> lignes = Arrays.stream(PaysUemoa.values())
                .map(p -> new LignePays(p, etablissements.countByPaysCode(p.getCode()),
                        collectes.findById(p.getCode()).orElse(null)))
                .toList();
        model.addAttribute("lignes", lignes);
        model.addAttribute("total", lignes.stream().mapToLong(LignePays::nombre).sum());
        model.addAttribute("enCours", collecte.estEnCours());
        return "plateforme/annuaire";
    }

    @PostMapping("/actualiser")
    public String actualiser(RedirectAttributes ra) {
        if (collecte.lancerEnArrierePlan()) {
            ra.addFlashAttribute("ok", "Collecte lancée. Elle prend une à deux minutes : "
                    + "rechargez la page pour suivre l'avancement.");
        } else {
            ra.addFlashAttribute("erreur", "Une collecte est déjà en cours.");
        }
        return "redirect:/plateforme/annuaire";
    }
}
