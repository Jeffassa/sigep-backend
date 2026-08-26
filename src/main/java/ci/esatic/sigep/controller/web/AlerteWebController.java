package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Salle;
import ci.esatic.sigep.entity.StatutDemande;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.DemandeRattrapageRepository;
import ci.esatic.sigep.repository.SalleRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import ci.esatic.sigep.service.RattrapageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalTime;

/** Espace admin : alertes (séances non émargées, comptes/rattrapages en attente) + décisions rattrapage. */
@Controller
@RequiredArgsConstructor
public class AlerteWebController {

    private final SeanceRepository seanceRepository;
    private final DemandeRattrapageRepository rattrapageRepository;
    private final EnseignantRepository enseignantRepository;
    private final SalleRepository salleRepository;
    private final RattrapageService rattrapageService;
    private final ci.esatic.sigep.repository.EmargementRepository emargementRepository;
    private final ci.esatic.sigep.service.EmargementService emargementService;

    @GetMapping("/admin/alertes")
    public String alertes(Model model) {
        model.addAttribute("seancesNonEmargees",
                seanceRepository.findSeancesNonEmargees(LocalDate.now(), LocalTime.now()));
        model.addAttribute("rattrapagesEnAttente",
                rattrapageRepository.findByStatutOrderByDateCreationDesc(StatutDemande.EN_ATTENTE));
        model.addAttribute("enseignantsEnAttente",
                enseignantRepository.findByStatutOrderByNomAsc(StatutEnseignant.PENDING));
        model.addAttribute("emargementsHorsLigne",
                emargementRepository.findEmargementsHorsLigneEnAttente().stream().limit(50).toList());
        model.addAttribute("salles", salleRepository.findAll());
        return "admin/alertes";
    }

    /** Accepte une demande de rattrapage : crée la séance dans la salle choisie + email à l'enseignant. */
    @PostMapping("/admin/rattrapages/{id}/accepter")
    public String accepterRattrapage(@PathVariable Long id, @RequestParam Long salleId, RedirectAttributes ra) {
        try {
            Salle salle = salleRepository.findById(salleId)
                    .orElseThrow(() -> new IllegalArgumentException("Salle introuvable"));
            rattrapageService.accepterAvecSalle(id, salle);
            ra.addFlashAttribute("success",
                    "Demande acceptee : seance de rattrapage creee, enseignant notifie par email.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Erreur : " + e.getMessage());
        }
        return "redirect:/admin/alertes";
    }

    /** Refuse une demande de rattrapage + email à l'enseignant. */
    @PostMapping("/admin/rattrapages/{id}/refuser")
    public String refuserRattrapage(@PathVariable Long id, RedirectAttributes ra) {
        try {
            rattrapageService.refuser(id);
            ra.addFlashAttribute("success", "Demande refusee : enseignant notifie par email.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Erreur : " + e.getMessage());
        }
        return "redirect:/admin/alertes";
    }

    /** Valide un émargement hors-ligne en attente : la présence est confirmée (séance émargée). */
    @PostMapping("/admin/emargements/hors-ligne/{id}/valider")
    public String validerHorsLigne(@PathVariable Long id, RedirectAttributes ra) {
        try {
            emargementService.validerHorsLigne(id);
            ra.addFlashAttribute("success", "Emargement hors-ligne valide : presence confirmee.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Erreur : " + e.getMessage());
        }
        return "redirect:/admin/alertes";
    }

    /** Refuse un émargement hors-ligne en attente : la séance redevient à régulariser. */
    @PostMapping("/admin/emargements/hors-ligne/{id}/refuser")
    public String refuserHorsLigne(@PathVariable Long id, RedirectAttributes ra) {
        try {
            emargementService.refuserHorsLigne(id);
            ra.addFlashAttribute("success", "Emargement hors-ligne refuse : seance a regulariser.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Erreur : " + e.getMessage());
        }
        return "redirect:/admin/alertes";
    }
}
