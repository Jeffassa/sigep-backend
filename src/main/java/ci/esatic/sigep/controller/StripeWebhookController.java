package ci.esatic.sigep.controller;

import ci.esatic.sigep.service.StripePaymentService;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Webhook Stripe (public, signature vérifiée). Source de vérité du paiement : à la réception
 * de « checkout.session.completed » payé, enregistre le paiement et prolonge l'abonnement.
 * Endpoint à déclarer dans le dashboard Stripe → https://sigep.store/api/stripe/webhook.
 */
@RestController
@RequestMapping("/api/stripe")
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookController {

    @Value("${app.stripe.webhook-secret:}")
    private String webhookSecret;

    private final StripePaymentService stripePaymentService;

    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(@RequestBody String payload,
                                          @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (webhookSecret == null || webhookSecret.isBlank() || signature == null) {
            return ResponseEntity.badRequest().body("webhook non configuré");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (Exception e) {
            log.warn("Webhook Stripe : signature invalide ({})", e.getMessage());
            return ResponseEntity.badRequest().body("signature invalide");
        }

        if ("checkout.session.completed".equals(event.getType())) {
            Session session = (Session) event.getDataObjectDeserializer().getObject().orElse(null);
            if (session != null && "paid".equals(session.getPaymentStatus())) {
                Map<String, String> md = session.getMetadata();
                if (md != null && md.get("etablissementId") != null) {
                    try {
                        Long etabId = Long.valueOf(md.get("etablissementId"));
                        int mois = Integer.parseInt(md.getOrDefault("mois", "1"));
                        long montant = Long.parseLong(md.getOrDefault("montantFcfa", "0"));
                        stripePaymentService.traiterPaiementReussi(etabId, mois, montant, "Stripe " + session.getId());
                    } catch (NumberFormatException nfe) {
                        log.error("Webhook Stripe : métadonnées invalides {}", md);
                    }
                }
            }
        }
        return ResponseEntity.ok("");
    }
}
