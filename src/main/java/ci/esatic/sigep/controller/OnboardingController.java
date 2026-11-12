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
 * attente, le paiement est préparé chez Jèko, et la réponse donne l'adresse où payer. Le
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

    @org.springframework.beans.factory.annotation.Value("${app.base-url:https://sigep.store}")
    private String baseUrl;

    @PostMapping("/etablissements")
    public ResponseEntity<ApiResponse<Object>> inscrireEtablissement(
            @Valid @RequestBody InscriptionEtablissementRequest request) {
        ci.esatic.sigep.entity.Plan plan = planPayant(request.getPlan());
        boolean achat = plan != null && mobileMoneyService.achatEnLigne();

        OnboardingService.Inscription inscription = onboardingService.creer(request, achat);
        if (!achat) {
            return ResponseEntity.ok(ApiResponse.success(
                    "Dossier reçu ! Votre inscription est en cours d'analyse — vous recevrez un e-mail "
                    + "dès la validation de votre espace.", inscription.reponse()));
        }

        // L'espace existe (enregistré) : le paiement se prépare hors de la transaction d'inscription.
        // S'il échoue, l'inscription n'est pas perdue : la page de suivi propose de réessayer.
        int mois = request.getMois() == null ? 1 : request.getMois();
        java.util.Map<String, Object> donnees = new java.util.LinkedHashMap<>();
        donnees.put("email", inscription.admin().getEmail());
        try {
            var intent = mobileMoneyService.initierJeko(inscription.etablissement(), plan, mois,
                    request.getMoyen(), SUIVI_PAIEMENT);
            donnees.put("reference", intent.getReference());
            if (ci.esatic.sigep.service.JekoService.adresseDePaiementSure(intent.getPaymentUrl())) {
                donnees.put("paiementUrl", intent.getPaymentUrl());
            }
            envoyerLienDeSuivi(inscription, intent.getReference());
        } catch (RuntimeException e) {
            log.warn("Inscription {} : paiement non préparé ({})", inscription.etablissement().getId(), e.getMessage());
            mobileMoneyService.derniereIntention(inscription.etablissement().getId())
                    .ifPresent(i -> {
                        donnees.put("reference", i.getReference());
                        envoyerLienDeSuivi(inscription, i.getReference());
                    });
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Votre espace est créé. Il sera activé dès que votre paiement sera confirmé.", donnees));
    }

    /** L'administrateur garde par e-mail le chemin vers son paiement, s'il quitte la page. */
    private void envoyerLienDeSuivi(OnboardingService.Inscription inscription, String reference) {
        mailService.notifierPaiementAFinaliser(inscription.admin().getEmail(),
                inscription.reponse().getPrenom(), inscription.etablissement().getNom(),
                baseUrl + SUIVI_PAIEMENT + reference);
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
