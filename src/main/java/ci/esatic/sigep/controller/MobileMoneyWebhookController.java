package ci.esatic.sigep.controller;

import ci.esatic.sigep.security.SecurityUtils;
import ci.esatic.sigep.service.PaiementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Webhook Mobile Money GÉNÉRIQUE (C7) — indépendant de l'agrégateur (CinetPay / PayDunya /
 * Wave / Orange). Le déploiement mappe le callback de son agrégateur vers ce contrat commun :
 *   POST /api/paiement/mobile-money/webhook
 *   En-tête : X-Webhook-Secret: &lt;secret partagé&gt;
 *   Corps JSON : { etablissementId, mois, montant, reference }
 *
 * Protégé par un secret partagé (fail-closed : refusé tant qu'aucun secret n'est configuré).
 * Idempotent via la référence (réutilise PaiementService — même logique atomique que Stripe).
 * Aucun SDK externe : brancher l'agrégateur réel = un adaptateur qui poste ce contrat.
 */
@RestController
@RequestMapping("/api/paiement/mobile-money")
@RequiredArgsConstructor
@Slf4j
public class MobileMoneyWebhookController {

    private final PaiementService paiementService;

    @Value("${app.mobile-money.webhook-secret:}")
    private String webhookSecret;

    public record MobileMoneyEvent(Long etablissementId, Integer mois, Long montant, String reference) {}

    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(@RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
                                          @RequestBody MobileMoneyEvent event) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Webhook Mobile Money reçu mais aucun secret configuré (app.mobile-money.webhook-secret).");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Webhook non configuré");
        }
        if (secret == null || !SecurityUtils.constantTimeEquals(webhookSecret, secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Secret invalide");
        }
        if (event == null || event.etablissementId() == null || event.montant() == null
                || event.reference() == null || event.reference().isBlank()) {
            return ResponseEntity.badRequest().body("Charge utile invalide (etablissementId, montant, reference requis)");
        }
        int mois = event.mois() == null ? 1 : event.mois();
        paiementService.enregistrer(event.etablissementId(), mois, event.montant(),
                event.reference(), "Mobile Money");
        // 200 même si déjà traité (idempotent) : évite les relivraisons inutiles de l'agrégateur.
        return ResponseEntity.ok("OK");
    }
}
