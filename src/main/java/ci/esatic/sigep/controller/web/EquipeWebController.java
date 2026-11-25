package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.config.ProfilsAdmin;
import ci.esatic.sigep.dto.request.ChangerMotDePasseRequest;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.service.AuthService;
import ci.esatic.sigep.service.EtablissementCourantService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Comptes de la direction, de la scolarité et de la comptabilité d'un établissement. */
@Controller
@RequiredArgsConstructor
public class EquipeWebController {

    private final UserRepository userRepository;
    private final ci.esatic.sigep.service.EquipeService equipeService;
    private final AuthService authService;
    private final EtablissementCourantService etablissementCourantService;

    @GetMapping("/admin/equipe")
    public String equipe(Model model) {
        Map<String, String> profils = new LinkedHashMap<>();
        for (ERole r : List.of(ERole.ROLE_DIRECTION, ERole.ROLE_SCOLARITE, ERole.ROLE_COMPTABILITE)) {
            profils.put(r.name(), ProfilsAdmin.LIBELLES.get(r));
        }
        model.addAttribute("membres", equipeService.membres());
        model.addAttribute("profils", profils);
        return "admin/equipe";
    }

    @PostMapping("/admin/equipe")
    public String ajouter(@RequestParam String email, @RequestParam ERole profil, RedirectAttributes ra) {
        try {
            String adresse = equipeService.inviter(email, profil);
            ra.addFlashAttribute("success", "Accès créé : les identifiants sont envoyés à " + adresse + ".");
        } catch (MetierException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/equipe";
    }

    @PostMapping("/admin/equipe/{id}/acces")
    public String changerAcces(@PathVariable Long id, @RequestParam boolean actif, RedirectAttributes ra) {
        Etablissement etab = etablissementCourantService.courant();
        // users n'a pas de filtre tenant : l'appartenance à l'établissement se vérifie ici.
        User membre = userRepository.findById(id)
                .filter(u -> u.getEtablissement() != null && u.getEtablissement().getId().equals(etab.getId()))
                .filter(u -> u.getRoles().stream().allMatch(r -> ProfilsAdmin.LIBELLES.containsKey(r.getName())))
                .orElse(null);
        if (membre == null) {
            ra.addFlashAttribute("error", "Ce compte ne peut pas être modifié ici.");
            return "redirect:/admin/equipe";
        }
        membre.setEnabled(actif);
        userRepository.save(membre);
        ra.addFlashAttribute("success", actif ? "Accès rétabli." : "Accès retiré : ce compte est déconnecté.");
        return "redirect:/admin/equipe";
    }

    @GetMapping("/admin/mon-compte")
    public String monCompte() {
        return "admin/mon-compte";
    }

    @PostMapping("/admin/mon-compte")
    public String changerMotDePasse(@AuthenticationPrincipal User utilisateur, @RequestParam String ancien,
                                    @RequestParam String nouveau, @RequestParam String confirmation,
                                    RedirectAttributes ra) {
        // Mêmes règles que ChangerMotDePasseRequest, côté application mobile.
        if (!nouveau.equals(confirmation)) {
            ra.addFlashAttribute("error", "Le nouveau mot de passe et sa confirmation sont différents.");
            return "redirect:/admin/mon-compte";
        }
        java.util.List<String> manque = ci.esatic.sigep.security.PolitiqueMotDePasse.manquements(nouveau, utilisateur.getEmail());
        if (!manque.isEmpty()) {
            ra.addFlashAttribute("error", "Mot de passe trop faible. Il faut : " + String.join(", ", manque) + ".");
            return "redirect:/admin/mon-compte";
        }
        ChangerMotDePasseRequest demande = new ChangerMotDePasseRequest();
        demande.setAncienMotDePasse(ancien);
        demande.setNouveauMotDePasse(nouveau);
        try {
            authService.changerMotDePasse(utilisateur.getEmail(), demande);
        } catch (MetierException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/mon-compte";
        }
        ra.addFlashAttribute("success", "Mot de passe modifié.");
        return "redirect:/admin/dashboard";
    }
}
