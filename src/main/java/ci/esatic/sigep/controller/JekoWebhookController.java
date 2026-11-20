package ci.esatic.sigep.controller;

import ci.esatic.sigep.security.SecurityUtils;
import ci.esatic.sigep.service.JekoService;
import ci.esatic.sigep.service.MobileMoneyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Notifications de Jèko (publiques, signature vérifiée).
 *
 * <p><b>Un accélérateur, pas une preuve.</b> Une notification valide ne crédite rien par elle-même :
 * elle déclenche la même vérification que le retour du navigateur et le relanceur, qui relit
 * l'état chez Jèko et contrôle le montant. Même une signature contournée ne donnerait donc aucun
 * abonnement.
 *
 * <p><b>Signature.</b> Jèko envoie {@code Jeko-Signature}, le HMAC-SHA256 du corps BRUT avec le
 * secret du webhook, en hexadécimal minuscule. Il est recalculé sur les octets reçus, jamais sur
 * un JSON relu, dont l'ordre ou l'espacement diffèreraient.
 *
 * <p><b>Répondre vite.</b> Jèko attend une réponse en moins de 30 secondes et ne réessaie que trois
 * fois en trois secondes : une notification perdue l'est pour de bon, d'où le relanceur.
 */
@RestController
@RequestMapping("/api/paiement/jeko")
@RequiredArgsConstructor
@Slf4j
public class JekoWebhookController {

    private static final String HMAC = "HmacSHA256";
    private static final String EVENEMENT_PAIEMENT = "TRANSACTION_COMPLETED";

    @Value("${app.jeko.webhook-secret:}")
    private String secret;

    private final MobileMoneyService mobileMoneyService;
    private final ci.esatic.sigep.service.JournalSecuriteService journalSecurite;
    private final ObjectMapper objectMapper;

    @PostMapping("/webhook")
    public ResponseEntity<String> recevoir(@RequestBody String corps, HttpServletRequest requete) {
        // Sans secret configuré, on refuse : une notification non vérifiable n'est pas lue.
        if (secret == null || secret.isBlank()) {
            log.warn("Notification Jèko reçue, mais aucun secret de webhook n'est configuré : refusée.");
            return ResponseEntity.badRequest().body("webhook non configuré");
        }
        String signature = requete.getHeader("Jeko-Signature");
        String attendue = hmacHex(corps, secret.trim());
        if (signature == null || attendue == null
                || !SecurityUtils.constantTimeEquals(attendue, signature.trim().toLowerCase())) {
            log.warn("Notification Jèko refusée : signature {}.", signature == null ? "absente" : "invalide");
            journalSecurite.enregistrer(
                    ci.esatic.sigep.entity.TypeEvenement.WEBHOOK_SIGNATURE_INVALIDE,
                    signature == null ? ci.esatic.sigep.entity.SeveriteEvenement.ALERTE
                                      : ci.esatic.sigep.entity.SeveriteEvenement.CRITIQUE,
                    requete.getRemoteAddr(), null, requete.getRequestURI(),
                    signature == null ? "Notification Jeko sans signature" : "Signature Jeko non conforme au corps recu");
            // 401 : Jèko ne réessaie pas sur ce code, inutile qu'il insiste.
            return ResponseEntity.status(401).body("signature invalide");
        }

        String evenement = requete.getHeader("Jeko-Event");
        if (evenement != null && !EVENEMENT_PAIEMENT.equalsIgnoreCase(evenement.trim())) {
            return ResponseEntity.ok("");   // un autre évènement ne concerne pas les abonnements
        }
        try {
            JsonNode n = objectMapper.readTree(corps);
            String reference = n.path("transactionDetails").path("reference").asString("");
            if (!reference.startsWith(JekoService.PREFIXE_REFERENCE)) {
                // Un paiement reçu par le magasin sans passer par SIGEP : rien à créditer ici.
                return ResponseEntity.ok("");
            }
            log.info("Notification Jèko : ref={} statut={}", reference, n.path("status").asString(""));
            mobileMoneyService.verifierEtCrediter(reference);
        } catch (Exception e) {
            // 500 : Jèko réessaie ; le traitement est idempotent, un second passage ne crédite pas deux fois.
            log.error("Notification Jèko : traitement échoué : {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("erreur de traitement");
        }
        return ResponseEntity.ok("");
    }

    private String hmacHex(String corps, String cle) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(cle.getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal(corps.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("Notification Jèko : calcul de signature impossible : {}", e.getMessage());
            return null;
        }
    }
}
