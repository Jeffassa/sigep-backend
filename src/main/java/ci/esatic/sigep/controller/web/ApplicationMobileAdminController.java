package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.ApplicationMobile;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.service.ApplicationMobileService;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.service.MailService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Console de l'établissement : télécharger l'application Android et en envoyer le lien aux
 * enseignants, à tous d'un coup ou à un seul. Le fichier est trop lourd pour une pièce jointe :
 * l'e-mail porte le lien de téléchargement de sigep.store.
 */
@Controller
@RequiredArgsConstructor
public class ApplicationMobileAdminController {

    public static final String PAGE = "/admin/enseignants/application-mobile";

    /** Un envoi à tous par établissement et par quart d'heure : un double clic n'écrit pas deux fois. */
    static final Duration DELAI_ENVOI_GROUPE = Duration.ofMinutes(15);

    private final ApplicationMobileService applicationMobileService;
    private final EtablissementCourantService etablissementCourantService;
    private final EnseignantRepository enseignantRepository;
    private final MailService mailService;

    @Value("${app.base-url:https://sigep.store}")
    private String baseUrl;

    private final Cache<Long, Boolean> envoisGroupes = Caffeine.newBuilder()
            .expireAfterWrite(DELAI_ENVOI_GROUPE).maximumSize(10_000).build();

    @GetMapping(PAGE)
    public String page(Model model) {
        Etablissement e = etablissementCourantService.courant();
        Optional<ApplicationMobile> app = applicationMobileService.courante().filter(a -> a.getMorceaux() > 0);
        List<Enseignant> joignables = e == null ? List.of() : enseignantRepository.joignables(e.getId(), ci.esatic.sigep.entity.StatutEnseignant.VALIDATED);
        model.addAttribute("apk", app.orElse(null));
        model.addAttribute("tailleMo", app.map(a -> Math.max(1, Math.round(a.getTaille() / 1048576.0))).orElse(0L));
        model.addAttribute("lien", lien());
        model.addAttribute("cheminTelechargement", ApplicationMobileController.CHEMIN);
        model.addAttribute("enseignants", joignables);
        return "admin/application-mobile";
    }

    /** Envoie le lien à tous les enseignants joignables, ou au seul enseignant choisi. */
    @PostMapping(PAGE + "/envoyer")
    public String envoyer(@RequestParam(required = false) Long enseignantId, RedirectAttributes ra) {
        Etablissement e = etablissementCourantService.courant();
        Optional<ApplicationMobile> app = applicationMobileService.courante().filter(a -> a.getMorceaux() > 0);
        if (e == null || app.isEmpty()) {
            ra.addFlashAttribute("erreur", "L'application n'est pas encore disponible au téléchargement.");
            return "redirect:" + PAGE;
        }

        List<Enseignant> joignables = enseignantRepository.joignables(e.getId(), ci.esatic.sigep.entity.StatutEnseignant.VALIDATED);
        List<Enseignant> destinataires;
        if (enseignantId != null) {
            destinataires = joignables.stream().filter(x -> x.getId().equals(enseignantId)).toList();
            if (destinataires.isEmpty()) {
                ra.addFlashAttribute("erreur", "Cet enseignant n'a pas d'adresse e-mail ou n'a pas de compte actif.");
                return "redirect:" + PAGE;
            }
        } else {
            if (joignables.isEmpty()) {
                ra.addFlashAttribute("erreur", "Aucun enseignant actif n'a d'adresse e-mail.");
                return "redirect:" + PAGE;
            }
            if (envoisGroupes.asMap().putIfAbsent(e.getId(), Boolean.TRUE) != null) {
                ra.addFlashAttribute("erreur", "Le lien vient déjà de partir vers tous les enseignants. "
                        + "Réessayez dans un quart d'heure, ou envoyez-le à un enseignant en particulier.");
                return "redirect:" + PAGE;
            }
            destinataires = joignables;
        }

        String version = app.get().getVersion();
        for (Enseignant x : destinataires) {
            mailService.notifierApplicationMobile(e.getEmailFrom(), x.getUser().getEmail(), x.getPrenom(),
                    e.getNomEffectif(), version, lien());
        }
        ra.addFlashAttribute("ok", destinataires.size() == 1
                ? "Lien envoyé à " + destinataires.get(0).getUser().getEmail() + "."
                : "Lien envoyé à " + destinataires.size() + " enseignants.");
        return "redirect:" + PAGE;
    }

    private String lien() {
        return baseUrl + ApplicationMobileController.CHEMIN;
    }
}
