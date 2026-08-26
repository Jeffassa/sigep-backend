package ci.esatic.sigep.controller;

import ci.esatic.sigep.service.StripePaymentService;
import ci.esatic.sigep.service.StripeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Webhook Stripe (public, signature vérifiée). Source de vérité du paiement : à la réception
 * de « checkout.session.completed » payé, enregistre le paiement et prolonge l'abonnement.
 * Endpoint à déclarer dans le dashboard Stripe → https://sigep.store/api/stripe/webhook.
 *
 * IMPORTANT : on lit le JSON BRUT (après vérification de signature) plutôt que la
 * désérialisation typée du SDK — cette dernière renvoie un objet VIDE quand la version d'API
 * du compte diffère de celle figée dans le SDK (le paiement passait alors inaperçu tout en
 * répondant 200 à Stripe). La lecture brute est indépendante de la version.
 */
@RestController
@RequestMapping("/api/stripe")
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookController {

    @Value("${app.stripe.webhook-secret:}")
    private String webhookSecret;

    private final StripePaymentService stripePaymentService;
    private final StripeService stripeService;
    private final ObjectMapper objectMapper;

    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(@RequestBody String payload,
                                          @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (webhookSecret == null || webhookSecret.isBlank() || signature == null) {
            log.warn("Webhook Stripe reçu mais non configuré (secret ou signature manquant).");
            return ResponseEntity.badRequest().body("webhook non configuré");
        }
        // Vérifie la signature (protège l'endpoint public). On ignore l'objet typé renvoyé.
        try {
            Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (Exception e) {
            log.warn("Webhook Stripe : signature invalide ({})", e.getMessage());
            return ResponseEntity.badRequest().body("signature invalide");
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String type = root.path("type").asText();
            if (!"checkout.session.completed".equals(type)) {
                log.info("Webhook Stripe : événement ignoré ({})", type);
                return ResponseEntity.ok("");
            }
            JsonNode obj = root.path("data").path("object");
            String paymentStatus = obj.path("payment_status").asText();
            JsonNode md = obj.path("metadata");
            String etabId = md.path("etablissementId").asText("");
            log.info("Webhook Stripe checkout.session.completed : status={} etablissementId={}", paymentStatus, etabId);

            if ("paid".equals(paymentStatus) && !etabId.isBlank()) {
                long montantFcfa = md.path("montantFcfa").asLong(0);
                // Réconciliation : le montant réellement encaissé (amount_total) doit correspondre
                // au montant attendu pour ce nombre de FCFA. Sinon on NE crédite PAS (défense contre
                // un écart metadata/paiement, ex. remise appliquée). 200 pour ne pas boucler Stripe.
                long attendu = stripeService.versUniteStripe(montantFcfa);
                long amountTotal = obj.path("amount_total").asLong(-1);
                if (amountTotal != attendu) {
                    log.warn("Webhook Stripe : montant encaisse ({}) != attendu ({}) pour etab {} — NON credite.",
                            amountTotal, attendu, etabId);
                    return ResponseEntity.ok("");
                }
                stripePaymentService.traiterPaiementReussi(
                        Long.valueOf(etabId),
                        md.path("mois").asInt(1),
                        montantFcfa,
                        "Stripe " + obj.path("id").asText());
            }
        } catch (Exception e) {
            // 500 → Stripe réessaiera (le traitement est idempotent, donc sûr).
            log.error("Webhook Stripe : traitement échoué : {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("erreur de traitement");
        }
        return ResponseEntity.ok("");
    }
}
