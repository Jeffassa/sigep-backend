package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.TypePeriode;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.PeriodeCalendrierRepository;
import ci.esatic.sigep.service.CalendrierService;
import ci.esatic.sigep.service.EtablissementCourantService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

/** Espace admin : calendrier de l'établissement, tolérance d'émargement et remplacements. */
@Controller
@RequiredArgsConstructor
public class CalendrierWebController {

    private static final int TOLERANCE_MAX_MINUTES = 120;

    private final CalendrierService calendrierService;
    private final PeriodeCalendrierRepository periodeRepository;
    private final EnseignantRepository enseignantRepository;
    private final EtablissementRepository etablissementRepository;
    private final EtablissementCourantService etablissementCourantService;

    @GetMapping("/admin/calendrier")
    public String calendrier(Model model) {
        model.addAttribute("periodes", periodeRepository.findAllByOrderByDebutAsc());
        model.addAttribute("enseignants", enseignantRepository.findByStatutOrderByNomAsc(StatutEnseignant.VALIDATED));
        model.addAttribute("etablissementCourant", etablissementCourantService.courant());
        return "admin/calendrier";
    }

    @PostMapping("/admin/calendrier/tolerance")
    public String tolerance(@RequestParam int avant, @RequestParam int apres, RedirectAttributes ra) {
        var etab = etablissementCourantService.courant();
        if (etab == null || avant < 0 || apres < 0 || avant > TOLERANCE_MAX_MINUTES || apres > TOLERANCE_MAX_MINUTES) {
            ra.addFlashAttribute("error", "La tolérance doit rester entre 0 et " + TOLERANCE_MAX_MINUTES + " minutes.");
            return "redirect:/admin/calendrier";
        }
        etab.setToleranceAvantMinutes(avant);
        etab.setToleranceApresMinutes(apres);
        etablissementRepository.save(etab);
        ra.addFlashAttribute("success", "Tolérance d'émargement enregistrée.");
        return "redirect:/admin/calendrier";
    }

    @PostMapping("/admin/calendrier/periodes")
    public String ajouter(@RequestParam TypePeriode type, @RequestParam String libelle,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin,
                          @RequestParam(defaultValue = "false") boolean retirerSeances,
                          RedirectAttributes ra) {
        try {
            int retirees = calendrierService.ajouterPeriode(type, libelle, debut, fin, retirerSeances);
            ra.addFlashAttribute("success", "« " + libelle.trim() + " » ajouté au calendrier"
                    + (retirees > 0 ? " : " + retirees + " séance(s) non émargée(s) retirée(s)." : "."));
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/calendrier";
    }

    @PostMapping("/admin/calendrier/periodes/{id}/supprimer")
    public String supprimer(@PathVariable Long id, RedirectAttributes ra) {
        periodeRepository.deleteById(id);
        ra.addFlashAttribute("success", "Période retirée du calendrier. Les séances déjà retirées ne reviennent pas.");
        return "redirect:/admin/calendrier";
    }

    @PostMapping("/admin/calendrier/remplacement")
    public String remplacer(@RequestParam Long absent, @RequestParam Long remplacant,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin,
                            RedirectAttributes ra) {
        try {
            CalendrierService.Remplacement r = calendrierService.remplacer(absent, remplacant, debut, fin);
            ra.addFlashAttribute(r.remplacees() > 0 || r.conflits() == 0 ? "success" : "error",
                    r.remplacees() + " séance(s) confiée(s) au remplaçant"
                            + (r.conflits() > 0 ? ", " + r.conflits() + " laissée(s) : il enseigne déjà sur ces créneaux." : "."));
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/calendrier";
    }
}
