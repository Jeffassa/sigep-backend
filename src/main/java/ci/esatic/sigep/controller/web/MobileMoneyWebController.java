package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.PaiementIntent;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.StatutIntent;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.PaiementIntentRepository;
import ci.esatic.sigep.service.MobileMoneyService;
import ci.esatic.sigep.service.NovaSendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * Paiement Mobile Money automatisé (NovaSend) côté établissement.
 *
 * <p>Parcours : l'admin choisit plan + durée + opérateur + numéro → une demande est poussée sur
 * son téléphone → une page d'attente sonde l'état → dès confirmation, l'abonnement est prolongé.
 *
 * <p>SECURITE : chaque accès à une intention vérifie qu'elle appartient bien à l'établissement
 * du compte connecté (sinon un admin pourrait consulter/valider la transaction d'un autre
 * établissement en devinant une référence). Le crédit n'est jamais déduit de la redirection :
 * il provient toujours d'une vérification serveur auprès de NovaSend.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class MobileMoneyWebController {

    private final MobileMoneyService mobileMoneyService;
    private final PaiementIntentRepository intentRepository;

    /** Lance la demande de paiement chez l'opérateur. */
    @PostMapping("/admin/abonnement/momo")
    public String initier(@AuthenticationPrincipal User user,
                          @RequestParam(defaultValue = "PRO") String plan,
                          @RequestParam(defaultValue = "1") int mois,
                          @RequestParam String provider,
                          @RequestParam String msisdn,
                          @RequestParam(required = false) String otp,
                          RedirectAttributes ra) {
        Etablissement e = user == null ? null : user.getEtablissement();
        if (e == null) {
            ra.addFlashAttribute("erreurAbo", "Compte sans établissement.");
            return "redirect:/admin/abonnement";
        }
        Plan choisi;
        try {
            choisi = Plan.valueOf(plan == null ? "PRO" : plan.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            choisi = Plan.PRO;
        }
        try {
            PaiementIntent intent = mobileMoneyService.initier(
                    e, choisi, mois, msisdn, provider, otp, e.getNomEffectif());
            return "redirect:/admin/abonnement/momo/attente?ref=" + intent.getReference();
        } catch (NovaSendService.NovaSendException ex) {
            ra.addFlashAttribute("erreurAbo", ex.getMessage());
            return "redirect:/admin/abonnement";
        } catch (Exception ex) {
            log.error("Initiation Mobile Money échouée : {}", ex.getMessage());
            ra.addFlashAttribute("erreurAbo", "Paiement Mobile Money indisponible. Réessayez plus tard.");
            return "redirect:/admin/abonnement";
        }
    }

    /** Page d'attente : instructions par opérateur + sondage de l'état. */
    @GetMapping("/admin/abonnement/momo/attente")
    public String attente(@AuthenticationPrincipal User user,
                          @RequestParam("ref") String reference,
                          Model model, RedirectAttributes ra) {
        PaiementIntent intent = intentAutorisee(user, reference);
        if (intent == null) {
            ra.addFlashAttribute("erreurAbo", "Transaction introuvable.");
            return "redirect:/admin/abonnement";
        }
        model.addAttribute("intent", intent);
        model.addAttribute("attenteDepassee", mobileMoneyService.attenteDepassee(intent));
        // paymentUrl vient d'un tiers : on ne l'injecte dans un href que si c'est bien du HTTPS
        // (un schéma javascript:/data: serait une injection dans la page admin).
        String url = intent.getPaymentUrl();
        model.addAttribute("paymentUrlSure",
                (url != null && url.startsWith("https://")) ? url : null);
        return "admin/momo-attente";
    }

    /** État courant de la transaction (sondé par la page d'attente). Vérifie côté serveur. */
    @GetMapping("/admin/abonnement/momo/statut")
    @ResponseBody
    public Map<String, Object> statut(@AuthenticationPrincipal User user,
                                      @RequestParam("ref") String reference) {
        PaiementIntent intent = intentAutorisee(user, reference);
        if (intent == null) {
            return Map.of("statut", "INCONNU");
        }
        StatutIntent statut = mobileMoneyService.verifierEtCrediter(reference);
        PaiementIntent frais = intentRepository.findByReference(reference).orElse(intent);
        return Map.of(
                "statut", statut == null ? "INCONNU" : statut.name(),
                "message", frais.getMessage() == null ? "" : frais.getMessage());
    }

    /** Retour depuis l'opérateur : on RE-VÉRIFIE côté serveur, la redirection ne prouve rien. */
    @GetMapping("/admin/abonnement/momo/retour")
    public String retour(@AuthenticationPrincipal User user,
                         @RequestParam("ref") String reference,
                         RedirectAttributes ra) {
        PaiementIntent intent = intentAutorisee(user, reference);
        if (intent == null) {
            ra.addFlashAttribute("erreurAbo", "Transaction introuvable.");
            return "redirect:/admin/abonnement";
        }
        StatutIntent statut = mobileMoneyService.verifierEtCrediter(reference);
        if (statut == StatutIntent.REUSSI) {
            // Flash (état serveur) et non paramètre d'URL : un bandeau « paiement confirmé »
            // ne doit pas pouvoir s'afficher en tapant simplement une adresse.
            ra.addFlashAttribute("momoConfirme", Boolean.TRUE);
            return "redirect:/admin/abonnement";
        }
        if (statut == StatutIntent.A_VERIFIER) {
            // On n'affirme JAMAIS « aucun montant débité » : l'argent est peut-être parti.
            ra.addFlashAttribute("erreurAbo",
                    "Votre paiement est en cours de vérification. Si votre compte a été débité, "
                    + "votre abonnement sera activé sous peu — inutile de payer à nouveau.");
            return "redirect:/admin/abonnement";
        }
        if (statut == StatutIntent.ECHOUE || statut == StatutIntent.EXPIRE) {
            ra.addFlashAttribute("erreurAbo", "Paiement non abouti. Aucun montant n'a été crédité.");
            return "redirect:/admin/abonnement";
        }
        // Toujours en cours : on renvoie sur la page d'attente plutôt que d'annoncer un succès.
        return "redirect:/admin/abonnement/momo/attente?ref=" + reference;
    }

    /** Intention appartenant bien à l'établissement du compte connecté, sinon null. */
    private PaiementIntent intentAutorisee(User user, String reference) {
        if (user == null || user.getEtablissement() == null || reference == null) return null;
        PaiementIntent intent = intentRepository.findByReference(reference).orElse(null);
        if (intent == null) return null;
        return intent.getEtablissementId().equals(user.getEtablissement().getId()) ? intent : null;
    }
}
