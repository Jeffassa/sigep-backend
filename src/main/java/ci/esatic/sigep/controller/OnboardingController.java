package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.request.InscriptionEtablissementRequest;
import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.AuthResponse;
import ci.esatic.sigep.service.OnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inscription publique d'un établissement.
 *
 * <p>Inscription gratuite : le dossier est examiné par la plateforme, comme avant. Plan payant
 * choisi sur la page d'accueil, avec un paiement en ligne disponible : l'espace est créé en
 * attente et un e-mail mène à la page de paiement, où le client choisit durée et moyen. Le
 * paiement confirmé active l'espace, sans intervention.
 */
@RestController
@RequestMapping("/api/saas")
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class OnboardingController {

    /** Page publique où l'on suit un paiement d'inscription ; la référence y est ajoutée. */
    public static final String SUIVI_PAIEMENT = "/inscription/paiement?ref=";

    private final OnboardingService onboardingService;
    private final ci.esatic.sigep.service.MobileMoneyService mobileMoneyService;
    private final ci.esatic.sigep.tenant.plan.PlanService planService;
    private final ci.esatic.sigep.service.MailService mailService;
    private final ci.esatic.sigep.service.JetonPaiementInscription jetons;

    @org.springframework.beans.factory.annotation.Value("${app.base-url:https://sigep.store}")
    private String baseUrl;

    /** Suite d'une inscription gratuite : la plateforme examine le dossier. */
    public static final String SUITE_EXAMEN = "examen";
    /** Suite d'une inscription à un plan payant : le lien de paiement part par e-mail. */
    public static final String SUITE_PAIEMENT = "paiement";

    /**
     * La réponse ne dépend que du plan demandé, jamais de l'adresse : une adresse qui a déjà un
     * compte reçoit la même réponse qu'une nouvelle (son titulaire est prévenu par e-mail). Pour
     * la même raison, le lien de paiement n'est remis que par e-mail : il prouve en plus que le
     * client reçoit bien les messages de l'adresse qu'il a donnée.
     */
    @PostMapping("/etablissements")
    public ResponseEntity<ApiResponse<Object>> inscrireEtablissement(
            @Valid @RequestBody InscriptionEtablissementRequest request) {
        ci.esatic.sigep.entity.Plan plan = planPayant(request.getPlan());
        boolean achat = plan != null && mobileMoneyService.achatEnLigne();

        java.util.Optional<OnboardingService.Inscription> creee = onboardingService.demander(request, achat);
        if (achat) {
            // L'espace existe en attente : la page de paiement, où le client choisit la durée et
            // son moyen de paiement, ne s'ouvre que depuis l'e-mail.
            creee.ifPresent(inscription -> mailService.notifierPaiementAFinaliser(
                    inscription.admin().getEmail(), inscription.reponse().getPrenom(),
                    inscription.etablissement().getNom(),
                    baseUrl + pagePaiement(jetons.creer(inscription.etablissement().getId()), plan)));
        }

        java.util.Map<String, Object> donnees = new java.util.LinkedHashMap<>();
        donnees.put("email", request.getAdminEmail());
        donnees.put("suite", achat ? SUITE_PAIEMENT : SUITE_EXAMEN);
        return ResponseEntity.ok(ApiResponse.success(achat
                ? "Vérifiez votre boîte mail : nous vous avons envoyé le lien pour payer et activer votre espace."
                : "Dossier reçu ! Votre inscription est en cours d'analyse — vous recevrez un e-mail "
                  + "dès la validation de votre espace.", donnees));
    }

    /** Adresse de la page de paiement d'une inscription. */
    public static String pagePaiement(String jeton, ci.esatic.sigep.entity.Plan plan) {
        return "/inscription/payer?jeton=" + java.net.URLEncoder.encode(jeton, java.nio.charset.StandardCharsets.UTF_8)
                + (plan == null ? "" : "&plan=" + plan.name());
    }

    /** Plan payant achetable en ligne, sinon null (inscription gratuite). */
    private ci.esatic.sigep.entity.Plan planPayant(String saisie) {
        if (saisie == null || saisie.isBlank()) return null;
        try {
            ci.esatic.sigep.entity.Plan p = ci.esatic.sigep.entity.Plan.valueOf(saisie.trim().toUpperCase());
            return planService.estAchetable(p) ? p : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
