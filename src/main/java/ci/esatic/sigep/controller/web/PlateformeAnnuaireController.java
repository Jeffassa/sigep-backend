package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.annuaire.AnnuaireCollecte;
import ci.esatic.sigep.annuaire.AnnuaireCollecteRepository;
import ci.esatic.sigep.annuaire.AnnuaireCollecteService;
import ci.esatic.sigep.annuaire.AnnuaireEtablissementRepository;
import ci.esatic.sigep.annuaire.EtablissementOfficielRepository;
import ci.esatic.sigep.annuaire.ImportOfficiel;
import ci.esatic.sigep.annuaire.ImportOfficielRepository;
import ci.esatic.sigep.annuaire.ListesOfficiellesService;
import ci.esatic.sigep.annuaire.PaysUemoa;
import ci.esatic.sigep.annuaire.ReconnaissanceService;
import ci.esatic.sigep.annuaire.SourceOfficielle;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.StatutReconnaissance;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EtablissementRepository;
import lombok.RequiredArgsConstructor;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Espace plateforme : l'annuaire des écoles de Côte d'Ivoire et sa collecte, les listes officielles
 * des établissements autorisés par l'État, et les établissements clients à vérifier.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequestMapping("/plateforme/annuaire")
public class PlateformeAnnuaireController {

    private final AnnuaireEtablissementRepository etablissements;
    private final AnnuaireCollecteRepository collectes;
    private final AnnuaireCollecteService collecte;
    private final EtablissementOfficielRepository officiels;
    private final ImportOfficielRepository imports;
    private final ListesOfficiellesService listesOfficielles;
    private final ReconnaissanceService reconnaissance;
    private final EtablissementRepository clients;

    public record LignePays(PaysUemoa pays, long nombre, AnnuaireCollecte derniere) {
    }

    public record LigneListe(SourceOfficielle source, long nombre, ImportOfficiel dernier) {
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

        List<LigneListe> listes = Arrays.stream(SourceOfficielle.values())
                .map(s -> new LigneListe(s, officiels.countBySource(s), imports.findById(s).orElse(null)))
                .toList();
        model.addAttribute("listes", listes);
        model.addAttribute("totalOfficiels", listes.stream().mapToLong(LigneListe::nombre).sum());
        model.addAttribute("importEnCours", listesOfficielles.estEnCours());
        String classement = listesOfficielles.getClassementMena();
        java.util.regex.Matcher session = classement == null ? null
                : java.util.regex.Pattern.compile("BEPC_SESSION_([0-9]{4})").matcher(classement);
        model.addAttribute("sessionMena", session != null && session.find() ? session.group(1) : null);

        model.addAttribute("aVerifier", clients.findByReconnaissanceOrderByDateCreationDesc(StatutReconnaissance.A_VERIFIER));
        model.addAttribute("reconnus", clients.findByReconnaissanceOrderByDateCreationDesc(StatutReconnaissance.RECONNU));
        List<Etablissement> decides = new ArrayList<>();
        decides.addAll(clients.findByReconnaissanceOrderByDateCreationDesc(StatutReconnaissance.VERIFIE));
        decides.addAll(clients.findByReconnaissanceOrderByDateCreationDesc(StatutReconnaissance.NON_RECONNU));
        model.addAttribute("decides", decides);
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

    @PostMapping("/officiels/actualiser")
    public String actualiserOfficiels(RedirectAttributes ra) {
        if (listesOfficielles.lancerEnArrierePlan()) {
            ra.addFlashAttribute("ok", "Import des listes officielles lancé. Il prend une minute environ : "
                    + "rechargez la page pour suivre l'avancement.");
        } else {
            ra.addFlashAttribute("erreur", "Un import est déjà en cours.");
        }
        return "redirect:/plateforme/annuaire#officiels";
    }

    @PostMapping("/reconnaissance/{id}")
    public String decider(@PathVariable Long id, @RequestParam StatutReconnaissance decision,
                          @RequestParam(required = false) String constat,
                          @AuthenticationPrincipal User auteur, RedirectAttributes ra) {
        try {
            Etablissement e = reconnaissance.decider(id, decision, constat,
                    auteur == null ? "super administrateur" : auteur.getEmail());
            ra.addFlashAttribute("ok", "« " + e.getNom() + " » : " + e.getReconnaissance().getLibelle().toLowerCase() + ".");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("erreur", ex.getMessage());
        }
        return "redirect:/plateforme/annuaire#a-verifier";
    }
}
