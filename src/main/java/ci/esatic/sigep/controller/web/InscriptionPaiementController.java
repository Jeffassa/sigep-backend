package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.controller.OnboardingController;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.PaiementIntent;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.StatutEtablissement;
import ci.esatic.sigep.entity.StatutIntent;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.PaiementIntentRepository;
import ci.esatic.sigep.service.JekoService;
import ci.esatic.sigep.service.JetonPaiementInscription;
import ci.esatic.sigep.service.MobileMoneyService;
import ci.esatic.sigep.tenant.plan.PlanService;
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

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les pages publiques qui suivent une inscription à un plan payant.
 *
 * <ul>
 *   <li><b>La page de paiement</b> ({@code /inscription/payer}) : le client y choisit son plan, la
 *       durée et son moyen de paiement, voit le total, et part payer chez Jèko. Il n'a pas encore
 *       d'espace actif, donc pas de connexion : un jeton signé, reçu aussi par e-mail, lui ouvre le
 *       paiement de son établissement, et de rien d'autre.</li>
 *   <li><b>La page de suivi</b> ({@code /inscription/paiement}) : Jèko y renvoie le client. Elle relit
 *       l'état chez Jèko — jamais la parole du navigateur — et c'est ce verdict qui active l'espace.
 *       La référence du paiement, imprévisible, en est la clé.</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class InscriptionPaiementController {

    /** Entre deux lectures chez Jèko pour une même référence : une page rechargée en boucle ne doit
     *  pas épuiser le quota d'appels de la plateforme. */
    private static final long INTERVALLE_MS = 4_000;

    /** Un même choix renvoyé dans ce délai est un double clic : on reprend la demande déjà créée. */
    private static final long DOUBLE_CLIC_SECONDES = 60;

    private final PaiementIntentRepository intentRepository;
    private final EtablissementRepository etablissementRepository;
    private final MobileMoneyService mobileMoneyService;
    private final JetonPaiementInscription jetons;
    private final PlanService planService;

    private final Map<String, Long> derniereLecture = new ConcurrentHashMap<>();

    // ─── La page de paiement ─────────────────────────────────────────────────

    @GetMapping("/inscription/payer")
    public String pagePaiement(@RequestParam("jeton") String jeton,
                               @RequestParam(required = false) String plan,
                               Model model, HttpServletResponse reponse) {
        reponse.setHeader("X-Robots-Tag", "noindex, nofollow");
        Etablissement e = etablissementDuJeton(jeton);
        if (e == null) {
            return "redirect:/inscription";
        }
        Plan choisi = planAchetable(plan);
        model.addAttribute("jeton", jeton);
        model.addAttribute("etablissementNom", e.getNomEffectif());
        model.addAttribute("dejaActif", e.getStatut() == StatutEtablissement.VALIDE);
        model.addAttribute("ouvert", e.getStatut() == StatutEtablissement.EN_ATTENTE && mobileMoneyService.achatEnLigne());
        model.addAttribute("planChoisi", choisi == null ? Plan.PRO.name() : choisi.name());
        model.addAttribute("prixPro", planService.prixMensuel(Plan.PRO));
        model.addAttribute("prixEnterprise", planService.prixMensuel(Plan.ENTERPRISE));
        // Un paiement laissé en cours (page de Jèko quittée) : on propose de le reprendre.
        mobileMoneyService.derniereIntention(e.getId())
                .filter(i -> i.getStatut() == StatutIntent.EN_COURS)
                .filter(i -> JekoService.adresseDePaiementSure(i.getPaymentUrl()))
                .ifPresent(i -> model.addAttribute("enCours", i));
        return "public/inscription-payer";
    }

    @PostMapping("/inscription/payer")
    public String payer(@RequestParam("jeton") String jeton,
                        @RequestParam(defaultValue = "PRO") String plan,
                        @RequestParam(defaultValue = "1") int mois,
                        @RequestParam(defaultValue = "") String moyen,
                        RedirectAttributes ra) {
        Etablissement e = etablissementDuJeton(jeton);
        if (e == null) {
            return "redirect:/inscription";
        }
        Plan choisi = planAchetable(plan);
        String retourPage = OnboardingController.pagePaiement(jeton, choisi);
        if (e.getStatut() != StatutEtablissement.EN_ATTENTE || choisi == null) {
            return "redirect:" + retourPage;
        }
        int duree = (mois == 3 || mois == 12) ? mois : 1;   // seules les durées proposées

        // Double clic : la même demande, toute récente, est reprise plutôt que doublée.
        var recente = mobileMoneyService.derniereIntention(e.getId())
                .filter(i -> i.getStatut() == StatutIntent.EN_COURS)
                .filter(i -> i.getPlan() == choisi && i.getMois() == duree && moyen.equalsIgnoreCase(i.getProvider()))
                .filter(i -> i.getDateCreation() != null
                        && i.getDateCreation().isAfter(LocalDateTime.now().minusSeconds(DOUBLE_CLIC_SECONDES)))
                .filter(i -> JekoService.adresseDePaiementSure(i.getPaymentUrl()));
        if (recente.isPresent()) {
            return "redirect:" + recente.get().getPaymentUrl();
        }

        try {
            PaiementIntent intent = mobileMoneyService.initierJeko(e, choisi, duree, moyen,
                    OnboardingController.SUIVI_PAIEMENT, false);
            if (JekoService.adresseDePaiementSure(intent.getPaymentUrl())) {
                return "redirect:" + intent.getPaymentUrl();
            }
            return "redirect:" + OnboardingController.SUIVI_PAIEMENT + intent.getReference();
        } catch (JekoService.JekoException ex) {
            ra.addFlashAttribute("erreur", ex.getMessage());
        } catch (Exception ex) {
            log.warn("Paiement d'inscription impossible (établissement {}) : {}", e.getId(), ex.getMessage());
            ra.addFlashAttribute("erreur", "Le paiement n'a pas pu être préparé. Réessayez dans un instant.");
        }
        return "redirect:" + retourPage;
    }

    // ─── La page de suivi ────────────────────────────────────────────────────

    @GetMapping("/inscription/paiement")
    public String suivi(@RequestParam("ref") String reference, Model model, HttpServletResponse reponse) {
        reponse.setHeader("X-Robots-Tag", "noindex, nofollow");
        PaiementIntent intent = intentPublique(reference);
        if (intent == null) {
            return "redirect:/inscription";
        }
        StatutIntent lu = verifier(intent);
        StatutIntent statut = lu == null ? intent.getStatut() : lu;
        Etablissement e = etablissementRepository.findById(intent.getEtablissementId()).orElse(null);
        model.addAttribute("intent", intent);
        model.addAttribute("statut", statut.name());
        model.addAttribute("etablissementNom", e == null ? "" : e.getNomEffectif());
        model.addAttribute("planLibelle", intent.getPlan() == Plan.PRO ? "Pro" : "Enterprise");
        model.addAttribute("paiementUrl",
                JekoService.adresseDePaiementSure(intent.getPaymentUrl()) ? intent.getPaymentUrl() : null);
        // Après un échec, retour à la page de paiement pour choisir à nouveau.
        boolean peutReessayer = e != null && e.getStatut() == StatutEtablissement.EN_ATTENTE
                && (statut == StatutIntent.ECHOUE || statut == StatutIntent.EXPIRE);
        model.addAttribute("pagePaiement", peutReessayer
                ? OnboardingController.pagePaiement(jetons.creer(e.getId()), intent.getPlan()) : null);
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

    // ─── Outils ──────────────────────────────────────────────────────────────

    private Etablissement etablissementDuJeton(String jeton) {
        return jetons.lire(jeton).flatMap(etablissementRepository::findById).orElse(null);
    }

    private Plan planAchetable(String saisie) {
        if (saisie == null || saisie.isBlank()) return Plan.PRO;
        try {
            Plan p = Plan.valueOf(saisie.trim().toUpperCase());
            return planService.estAchetable(p) ? p : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
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
}
