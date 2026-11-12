package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.controller.OnboardingController;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.PaiementIntent;
import ci.esatic.sigep.entity.StatutEtablissement;
import ci.esatic.sigep.entity.StatutIntent;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.PaiementIntentRepository;
import ci.esatic.sigep.service.JekoService;
import ci.esatic.sigep.service.MobileMoneyService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suivi public du paiement d'une inscription : la page où Jèko renvoie le nouveau client, et
 * d'où il peut reprendre un paiement échoué. Il n'a pas encore de compte actif : la référence
 * du paiement, imprévisible et reçue par e-mail, fait office de clé.
 *
 * <p>Comme partout, la page ne croit pas le retour du navigateur : elle relit l'état chez Jèko,
 * et c'est ce verdict qui active l'espace.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class InscriptionPaiementController {

    /** Entre deux lectures chez Jèko pour une même référence : une page rechargée en boucle ne doit
     *  pas épuiser le quota d'appels de la plateforme. */
    private static final long INTERVALLE_MS = 4_000;

    private final PaiementIntentRepository intentRepository;
    private final EtablissementRepository etablissementRepository;
    private final MobileMoneyService mobileMoneyService;

    private final Map<String, Long> derniereLecture = new ConcurrentHashMap<>();

    @GetMapping("/inscription/paiement")
    public String suivi(@RequestParam("ref") String reference, Model model, HttpServletResponse reponse) {
        reponse.setHeader("X-Robots-Tag", "noindex, nofollow");
        PaiementIntent intent = intentPublique(reference);
        if (intent == null) {
            return "redirect:/inscription";
        }
        StatutIntent statut = verifier(intent);
        Etablissement e = etablissementRepository.findById(intent.getEtablissementId()).orElse(null);
        model.addAttribute("intent", intent);
        model.addAttribute("statut", statut == null ? intent.getStatut().name() : statut.name());
        model.addAttribute("etablissementNom", e == null ? "" : e.getNomEffectif());
        model.addAttribute("planLibelle", intent.getPlan() == ci.esatic.sigep.entity.Plan.PRO ? "Pro" : "Enterprise");
        model.addAttribute("paiementUrl",
                JekoService.adresseDePaiementSure(intent.getPaymentUrl()) ? intent.getPaymentUrl() : null);
        model.addAttribute("peutReessayer", peutReessayer(intent, e));
        return "public/inscription-paiement";
    }

    /** État courant, pour la page qui se met à jour seule. */
    @GetMapping("/inscription/paiement/statut")
    @ResponseBody
    public Map<String, String> statut(@RequestParam("ref") String reference) {
        PaiementIntent intent = intentPublique(reference);
        if (intent == null) return Map.of("statut", "INCONNU");
        StatutIntent statut = verifier(intent);
        return Map.of("statut", statut == null ? intent.getStatut().name() : statut.name());
    }

    /**
     * Reprend un paiement : nouvel essai après un échec, ou retour sur la page Jèko d'un paiement
     * resté en cours. Le plan et la durée restent ceux de l'inscription ; seul le moyen change.
     */
    @PostMapping("/inscription/paiement/reessayer")
    public String reessayer(@RequestParam("ref") String reference,
                            @RequestParam(required = false) String moyen,
                            RedirectAttributes ra) {
        PaiementIntent intent = intentPublique(reference);
        if (intent == null) {
            return "redirect:/inscription";
        }
        Etablissement e = etablissementRepository.findById(intent.getEtablissementId()).orElse(null);
        if (intent.getStatut() == StatutIntent.EN_COURS && JekoService.adresseDePaiementSure(intent.getPaymentUrl())) {
            return "redirect:" + intent.getPaymentUrl();
        }
        if (!peutReessayer(intent, e)) {
            return "redirect:" + OnboardingController.SUIVI_PAIEMENT + reference;
        }
        String choisi = (moyen == null || moyen.isBlank()) ? intent.getProvider() : moyen;
        try {
            PaiementIntent nouvelle = mobileMoneyService.initierJeko(e, intent.getPlan(), intent.getMois(),
                    choisi == null ? "" : choisi.toLowerCase(), OnboardingController.SUIVI_PAIEMENT);
            if (JekoService.adresseDePaiementSure(nouvelle.getPaymentUrl())) {
                return "redirect:" + nouvelle.getPaymentUrl();
            }
            return "redirect:" + OnboardingController.SUIVI_PAIEMENT + nouvelle.getReference();
        } catch (JekoService.JekoException ex) {
            ra.addFlashAttribute("erreur", ex.getMessage());
        } catch (Exception ex) {
            log.warn("Reprise du paiement d'inscription impossible (ref={}) : {}", reference, ex.getMessage());
            ra.addFlashAttribute("erreur", "Le paiement n'a pas pu être relancé. Réessayez dans un instant.");
        }
        // La nouvelle tentative a peut-être laissé une trace : on suit la plus récente.
        String suite = mobileMoneyService.derniereIntention(intent.getEtablissementId())
                .map(PaiementIntent::getReference).orElse(reference);
        return "redirect:" + OnboardingController.SUIVI_PAIEMENT + suite;
    }

    /**
     * Seuls les paiements Jèko d'une inscription se suivent ici. Un paiement fait depuis l'espace
     * d'administration se suit dans l'espace, derrière la connexion.
     */
    private PaiementIntent intentPublique(String reference) {
        if (reference == null || !reference.startsWith(JekoService.PREFIXE_REFERENCE) || reference.length() > 64) {
            return null;
        }
        return intentRepository.findByReference(reference).orElse(null);
    }

    /** Lit l'état chez Jèko, au plus une fois toutes les quelques secondes par référence. */
    private StatutIntent verifier(PaiementIntent intent) {
        if (intent.getStatut() != StatutIntent.EN_COURS) return intent.getStatut();
        long maintenant = System.currentTimeMillis();
        Long precedente = derniereLecture.get(intent.getReference());
        if (precedente != null && maintenant - precedente < INTERVALLE_MS) return intent.getStatut();
        derniereLecture.put(intent.getReference(), maintenant);
        if (derniereLecture.size() > 10_000) derniereLecture.clear();   // borne mémoire
        return mobileMoneyService.verifierEtCrediter(intent.getReference());
    }

    /** Un nouvel essai n'a de sens que pour un espace encore en attente, après un échec. */
    private static boolean peutReessayer(PaiementIntent intent, Etablissement e) {
        return e != null && e.getStatut() == StatutEtablissement.EN_ATTENTE
                && (intent.getStatut() == StatutIntent.ECHOUE || intent.getStatut() == StatutIntent.EXPIRE);
    }
}
