package ci.esatic.sigep.controller;

import ci.esatic.sigep.security.SecurityUtils;
import ci.esatic.sigep.service.MobileMoneyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Webhook NovaSend (public, signature HMAC vérifiée).
 *
 * <p><b>Rôle : accélérateur, pas source de vérité.</b> Une notification valide ne crédite rien
 * directement : elle déclenche la même vérification serveur que le reste du système
 * ({@link MobileMoneyService#verifierEtCrediter}), qui interroge l'API de statut et contrôle le
 * montant et la devise face au contrat figé à l'initiation. Conséquence : même si la
 * vérification de signature était contournée, personne ne pourrait obtenir un abonnement — le
 * crédit dépend toujours de l'état réel du paiement chez NovaSend. Le webhook fait simplement
 * gagner le délai du relanceur périodique (confirmation quasi instantanée).
 *
 * <p><b>Signature.</b> NovaSend envoie {@code X-Signature-Value = HMAC_SHA256(corps, secret)}
 * en hexadécimal, avec un secret PROPRE AU WEBHOOK (distinct de la clé API). Le HMAC est
 * recalculé sur le CORPS BRUT reçu — jamais sur un JSON re-sérialisé, dont l'ordre des clés ou
 * l'espacement diffèrerait et invaliderait une signature pourtant légitime.
 */
@RestController
@RequestMapping("/api/paiement/novasend")
@RequiredArgsConstructor
@Slf4j
public class NovaSendWebhookController {

    private static final String HMAC_ALGO = "HmacSHA256";

    @Value("${app.novasend.webhook-secret:}")
    private String webhookSecret;

    private final MobileMoneyService mobileMoneyService;
    private final ObjectMapper objectMapper;

    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(@RequestBody String payload, HttpServletRequest requete) {
        String signature = chercherSignature(requete);
        // Fail-closed : sans secret configuré, on refuse plutôt que d'accepter un appel non vérifié.
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Webhook NovaSend reçu mais aucun secret n'est configuré — refusé.");
            return ResponseEntity.badRequest().body("webhook non configuré");
        }
        if (signature == null || signature.isBlank()) {
            // On journalise les NOMS des en-têtes reçus (jamais leurs valeurs) : sans cela,
            // impossible de savoir si le fournisseur signe sous un autre nom que celui annoncé.
            StringBuilder noms = new StringBuilder();
            for (var e = requete.getHeaderNames(); e != null && e.hasMoreElements(); ) {
                if (noms.length() > 0) noms.append(", ");
                noms.append(e.nextElement());
            }
            log.warn("Webhook NovaSend SANS signature — refusé. En-têtes reçus : [{}] · évènement : {}",
                    noms, resumeEvenement(payload));
            return ResponseEntity.badRequest().body("signature absente");
        }
        // Normalisation de casse uniquement (l'hexadécimal n'est pas sensible à la casse) :
        // la comparaison elle-même reste à temps constant.
        String recue = signature.trim().toLowerCase();
        String attendue = hmacHex(payload, webhookSecret);
        if (attendue == null || !SecurityUtils.constantTimeEquals(attendue, recue)) {
            log.warn("Webhook NovaSend : signature invalide — refusé.");
            return ResponseEntity.badRequest().body("signature invalide");
        }

        try {
            JsonNode racine = objectMapper.readTree(payload);
            String reference = racine.path("reference").asText("");
            if (reference.isBlank()) {
                log.warn("Webhook NovaSend : référence absente du corps.");
                return ResponseEntity.ok("");   // 200 : rien à rejouer, inutile que NovaSend insiste
            }
            log.info("Webhook NovaSend reçu : reference={} status={}",
                    reference, racine.path("status").asText(""));
            // Vérification serveur + crédit idempotent (même chemin que le retour navigateur
            // et le relanceur : aucun double crédit possible).
            mobileMoneyService.verifierEtCrediter(reference);
        } catch (Exception e) {
            // 500 → NovaSend réessaiera ; le traitement étant idempotent, c'est sans risque.
            log.error("Webhook NovaSend : traitement échoué : {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("erreur de traitement");
        }
        return ResponseEntity.ok("");
    }

    /**
     * Cherche la signature sous les différents noms d'en-tête employés par les fournisseurs.
     * La doc annonce X-Signature-Value, mais les notifications reçues n'en portent pas :
     * on tolère les variantes usuelles plutôt que de rejeter une notification légitime.
     */
    private String chercherSignature(HttpServletRequest requete) {
        String[] candidats = {"X-Signature-Value", "X-Signature", "X-Novasend-Signature",
                              "X-Webhook-Signature", "Signature", "X-Hub-Signature-256"};
        for (String nom : candidats) {
            String v = requete.getHeader(nom);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** Résumé NON personnel de l'évènement (jamais le corps entier : il contient un téléphone). */
    private String resumeEvenement(String payload) {
        try {
            JsonNode n = objectMapper.readTree(payload);
            return "type=" + n.path("type").asText("?")
                 + " status=" + n.path("status").asText("?")
                 + " reference=" + n.path("reference").asText("?");
        } catch (Exception e) {
            return "corps non JSON (" + (payload == null ? 0 : payload.length()) + " caractères)";
        }
    }

    /** HMAC-SHA256 du corps BRUT, en hexadécimal minuscule. */
    private String hmacHex(String corps, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return HexFormat.of().formatHex(mac.doFinal(corps.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("Webhook NovaSend : calcul HMAC impossible : {}", e.getMessage());
            return null;
        }
    }
}
