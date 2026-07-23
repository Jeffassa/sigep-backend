package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.service.EnseignantService;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.service.ImportService;
import ci.esatic.sigep.service.MailService;
import ci.esatic.sigep.tenant.TenantContext;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Espace admin : gestion des enseignants (annuaire, création, import, statut) + messagerie. */
@Controller
@RequiredArgsConstructor
public class EnseignantWebController {

    private final EnseignantRepository enseignantRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EnseignantService enseignantService;
    private final ImportService importService;
    private final MailService mailService;
    private final PlanService planService;
    private final EtablissementCourantService etablissementCourantService;

    @GetMapping("/admin/enseignants")
    public String enseignants(@RequestParam(defaultValue = "") String search,
                              @RequestParam(defaultValue = "") String departement,
                              @RequestParam(defaultValue = "0") int page,
                              Model model) {
        int size = 10;
        String searchParam = search.isBlank() ? null : search;
        String deptParam = departement.isBlank() ? null : departement;

        Page<Enseignant> pageResult = enseignantRepository.searchEnseignants(
                searchParam, deptParam, TenantContext.get(), PageRequest.of(page, size));

        model.addAttribute("enseignants", pageResult);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", pageResult.getTotalPages());
        model.addAttribute("totalElements", pageResult.getTotalElements());
        model.addAttribute("search", search);
        model.addAttribute("departement", departement);
        model.addAttribute("departements", enseignantRepository.findDistinctDepartements());

        return "admin/enseignants";
    }

    @GetMapping("/admin/enseignants/nouveau")
    public String nouvelEnseignantForm(Model model) {
        model.addAttribute("departements", enseignantRepository.findDistinctDepartements());
        return "admin/enseignant-form";
    }

    @PostMapping("/admin/enseignants")
    @Transactional
    public String creerEnseignant(@AuthenticationPrincipal User admin,
                                   @RequestParam String matricule,
                                   @RequestParam String nom,
                                   @RequestParam String prenom,
                                   @RequestParam(required = false) String departement,
                                   @RequestParam(required = false) String grade,
                                   @RequestParam String email,
                                   @RequestParam String password,
                                   RedirectAttributes ra) {
        // Quota du plan (Free ≤ 10 enseignants) : appliqué sur TOUS les chemins de création.
        Etablissement etabCourant = etablissementCourantService.courant();
        if (etabCourant != null
                && planService.quotaEnseignantsAtteint(etabCourant,
                    enseignantRepository.countByEtablissementId(etabCourant.getId()))) {
            ra.addFlashAttribute("error", "Quota d'enseignants atteint ("
                    + etabCourant.getMaxEnseignants()
                    + ") pour le plan " + etabCourant.getPlan()
                    + ". Passez à un plan supérieur pour en ajouter davantage.");
            return "redirect:/admin/enseignants";
        }
        if (password == null || password.length() < 8
                || !password.matches("^(?=.*[A-Za-z])(?=.*\\d).+$")) {
            ra.addFlashAttribute("error",
                    "Le mot de passe doit faire au moins 8 caractères et contenir une lettre et un chiffre.");
            return "redirect:/admin/enseignants/nouveau";
        }
        if (enseignantRepository.existsByMatricule(matricule)) {
            ra.addFlashAttribute("error", "Ce matricule existe deja : " + matricule);
            return "redirect:/admin/enseignants/nouveau";
        }
        if (userRepository.existsByEmail(email)) {
            ra.addFlashAttribute("error", "Cet email est deja utilise : " + email);
            return "redirect:/admin/enseignants/nouveau";
        }

        Role role = roleRepository.findByName(ERole.ROLE_ENSEIGNANT).orElseThrow();

        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(password))
                .roles(Set.of(role))
                .build();
        userRepository.save(user);

        Enseignant enseignant = Enseignant.builder()
                .matricule(matricule)
                .nom(nom)
                .prenom(prenom)
                .departement(departement)
                .grade(grade)
                .statut(StatutEnseignant.PENDING)
                .user(user)
                .build();
        enseignantRepository.save(enseignant);

        ra.addFlashAttribute("success",
                "Enseignant " + prenom + " " + nom + " cree avec succes.");
        return "redirect:/admin/enseignants";
    }

    // Import en masse d'enseignants (Excel) : crée l'annuaire ; les profs s'inscrivent ensuite par matricule.
    @PostMapping("/admin/enseignants/import")
    public String importerEnseignants(@RequestParam("fichier") MultipartFile fichier, RedirectAttributes ra) {
        try {
            Map<String, Object> r = importService.importerEnseignants(fichier);
            String message = r.get("importes") + " enseignant(s) importé(s), "
                    + r.get("ignores") + " ignoré(s) (déjà présents).";
            @SuppressWarnings("unchecked")
            List<Integer> invalides = (List<Integer>) r.get("lignesInvalides");
            if (invalides != null && !invalides.isEmpty()) {
                message += " " + invalides.size() + " ligne(s) incomplète(s) non importée(s) : " + invalides + ".";
            }
            if (Boolean.TRUE.equals(r.get("quotaAtteint"))) {
                message += " Quota d'enseignants du plan atteint : le reste du fichier n'a pas été importé"
                        + " — passez à un plan supérieur pour continuer.";
            }
            ra.addFlashAttribute("success", message);
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Import impossible : " + e.getMessage());
        }
        return "redirect:/admin/enseignants";
    }

    // --- Messagerie : e-mail à un enseignant précis ou à tous ---
    @GetMapping("/admin/messages")
    public String messagesForm(Model model) {
        model.addAttribute("enseignants", enseignantRepository.findAll().stream()
                .filter(e -> e.getUser() != null)
                .toList());
        return "admin/messages";
    }

    @PostMapping("/admin/messages")
    public String envoyerMessage(@RequestParam String destinataire,
                                 @RequestParam String sujet,
                                 @RequestParam String corps,
                                 RedirectAttributes ra) {
        int envoyes = 0;
        if ("ALL".equals(destinataire)) {
            for (Enseignant e : enseignantRepository.findAll()) {
                if (e.getUser() != null && e.getUser().getEmail() != null) {
                    mailService.envoyerMessage(e.getUser().getEmail(), sujet, corps);
                    envoyes++;
                }
            }
        } else {
            Enseignant e = enseignantRepository.findById(Long.valueOf(destinataire)).orElse(null);
            if (e != null && e.getUser() != null) {
                mailService.envoyerMessage(e.getUser().getEmail(), sujet, corps);
                envoyes = 1;
            }
        }
        ra.addFlashAttribute("success", envoyes + " message(s) envoyé(s).");
        return "redirect:/admin/messages";
    }

    @PostMapping("/admin/enseignants/{id}/statut")
    public String updateStatut(@PathVariable Long id,
                               @RequestParam StatutEnseignant statut,
                               RedirectAttributes ra) {
        try {
            enseignantService.updateStatut(id, statut);
            ra.addFlashAttribute("success", "Statut mis a jour.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Mise à jour impossible.");
        }
        return "redirect:/admin/enseignants";
    }

    @PostMapping("/admin/enseignants/{id}/supprimer")
    public String supprimerEnseignant(@PathVariable Long id, RedirectAttributes ra) {
        enseignantRepository.findById(id).ifPresent(enseignantRepository::delete);
        ra.addFlashAttribute("success", "Enseignant supprime.");
        return "redirect:/admin/enseignants";
    }
}
