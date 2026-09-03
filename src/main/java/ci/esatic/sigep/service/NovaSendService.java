package ci.esatic.sigep.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client de l'API NovaSend (encaissement Mobile Money : WAVE / ORANGE / MOMO / MOOV).
 *
 * <p><b>Modèle de confirmation.</b> Après l'initiation, le paiement est confirmé par le client
 * sur son téléphone (push/USSD) ou via {@code paymentUrl}. NovaSend notifie par webhook signé,
 * mais celui-ci sert d'ACCÉLÉRATEUR : la source de vérité reste {@code GET /v1/payin/{reference}}.
 * Les URLs {@code successUrl}/{@code failureUrl} ne prouvent RIEN (n'importe qui peut les ouvrir) :
 * elles ramènent l'utilisateur, jamais elles ne créditent un abonnement.
 *
 * <p>Authentification : {@code Basic base64(api_key:api_client)}. Chaque initiation porte un
 * en-tête {@code X-Idempotency-Key} (la référence marchande, un UUID) afin qu'un rejeu réseau
 * ne crée pas deux transactions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NovaSendService {

    private final ObjectMapper objectMapper;

    @Value("${app.novasend.enabled:false}")
    private boolean enabled;

    @Value("${app.novasend.base-url:https://business.novasend.app}")
    private String baseUrl;

    /** Clé API (Paramètres &gt; Token). Partie « utilisateur » de l'authentification Basic. */
    @Value("${app.novasend.api-key:}")
    private String apiKey;

    /** Clé secrète associée. Partie « mot de passe » : Basic base64(cle_api:cle_secrete).
     *  Le couple détermine aussi l'environnement (sandbox ou production, strictement isolés). */
    @Value("${app.novasend.secret-key:}")
    private String secretKey;

    @Value("${app.novasend.country:CI}")
    private String country;

    @Value("${app.novasend.environment:sandbox}")
    private String environment;

    /** Mode test déclaré : l'argent est fictif alors que les abonnements crédités sont réels. */
    public boolean estSandbox() {
        return !"production".equalsIgnoreCase(environment);
    }

    /** Encaissement Mobile Money utilisable ? (activé + identifiants présents) */
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank()
                && secretKey != null && !secretKey.isBlank();   // isBlank() couvre déjà les espaces seuls
    }

    /** Opérateurs supportés par l'API Direct. */
    public boolean providerSupporte(String provider) {
        if (provider == null) return false;
        return switch (provider.toUpperCase()) {
            case "WAVE", "ORANGE", "MOMO", "MOOV" -> true;
            default -> false;
        };
    }

    /** Orange Money exige un code OTP obtenu par le client via #144*82#. */
    public boolean otpRequis(String provider) {
        return "ORANGE".equalsIgnoreCase(provider);
    }

    /** Construit UNE SEULE FOIS (un client par appel créerait un pool de connexions par requête). */
    private volatile RestClient client;

    /**
     * Timeouts COURTS et obligatoires : sans eux, un appel qui pend immobilise le thread
     * appelant sans limite. Combiné à un sondage régulier, cela suffirait à épuiser le pool
     * de connexions JDBC et à mettre toute l'application à genoux.
     */
    private RestClient client() {
        RestClient local = client;
        if (local != null) return local;
        synchronized (this) {
            if (client != null) return client;
            // On NETTOIE les identifiants : un espace ou un retour à la ligne collé par
            // mégarde dans une variable d'environnement corrompt l'en-tête Basic et produit
            // un 401 impossible à distinguer d'une mauvaise clé.
            String cle = apiKey == null ? "" : apiKey.trim();
            String sec = secretKey == null ? "" : secretKey.trim();
            if (!cle.equals(apiKey) || !sec.equals(secretKey)) {
                log.warn("NovaSend : des espaces parasites ont été retirés des identifiants "
                        + "(vérifiez NOVASEND_API_KEY / NOVASEND_SECRET_KEY dans l'environnement).");
            }
            String creds = Base64.getEncoder().encodeToString(
                    (cle + ":" + sec).getBytes(StandardCharsets.UTF_8));
            var httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3))
                    .build();
            var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(java.time.Duration.ofSeconds(8));
            client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(factory)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + creds)
                    .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "fr")
                    .build();
            return client;
        }
    }

    /**
     * Déclenche une demande de paiement Mobile Money (push/STK/USSD) chez l'opérateur.
     *
     * @param reference référence marchande unique (UUID) — sert aussi de clé d'idempotence.
     * @param otp       code OTP Orange Money (ignoré pour les autres opérateurs).
     * @throws NovaSendException si l'API refuse la demande (montant, identifiants, opérateur…).
     */
    public Reponse initierPayin(String reference, long montant, String msisdn, String provider,
                                String customerName, String otp,
                                String successUrl, String failureUrl) {
        Map<String, Object> payin = new LinkedHashMap<>();
        payin.put("amount", montant);
        payin.put("msisdn", msisdn);
        payin.put("provider", provider.toUpperCase());
        payin.put("country", country);
        if (otpRequis(provider) && otp != null && !otp.isBlank()) {
            payin.put("otp", otp.trim());
        }
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("successUrl", successUrl);
        action.put("failureUrl", failureUrl);

        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("reference", reference);
        if (customerName != null && !customerName.isBlank()) corps.put("customerName", customerName);
        corps.put("payin", payin);
        corps.put("action", action);

        try {
            String json = client().post()
                    .uri("/v1/direct/payin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Idempotency-Key", reference)
                    .body(corps)
                    .retrieve()
                    .body(String.class);
            return lire(json);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // On journalise le CODE et la RÉPONSE du fournisseur : sans eux, un refus
            // d'authentification est indiscernable d'un montant invalide, et le diagnostic
            // devient impossible sans redéployer.
            String reponseErreur = e.getResponseBodyAsString();
            log.warn("NovaSend : initiation refusée (ref={}) HTTP {} — réponse: {}",
                    reference, e.getStatusCode().value(),
                    reponseErreur == null ? "" : reponseErreur.substring(0, Math.min(reponseErreur.length(), 400)));
            throw new NovaSendException(messageSelonStatut(e.getStatusCode().value(), reponseErreur));
        } catch (Exception e) {
            log.warn("NovaSend : initiation impossible (ref={}) : {}", reference, e.toString());
            throw new NovaSendException("Le service de paiement est momentanément indisponible.");
        }
    }

    /**
     * Consulte l'état réel d'un paiement. SEULE source de vérité pour créditer un abonnement.
     *
     * <p>Le résultat distingue TROIS cas, distinction vitale : confondre « NovaSend injoignable »
     * avec « paiement introuvable » conduirait à clore des paiements réellement encaissés pendant
     * une simple panne réseau. Seul {@code INTROUVABLE} est un verdict du fournisseur ;
     * {@code INJOIGNABLE} veut dire « on ne sait pas encore », et impose de réessayer.
     */
    public Statut statut(String reference) {
        try {
            String json = client().get()
                    .uri("/v1/payin/{reference}", reference)
                    .retrieve()
                    .body(String.class);
            Reponse r = lire(json);
            return r == null ? new Statut(null, Etat.INJOIGNABLE) : new Statut(r, Etat.VERDICT);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // Verdict explicite du fournisseur : cette référence n'existe pas chez lui.
            log.warn("NovaSend : paiement introuvable (ref={})", reference);
            return new Statut(null, Etat.INTROUVABLE);
        } catch (Exception e) {
            // Panne réseau, 5xx, timeout, TLS… : on NE SAIT PAS. Ne rien clore sur cette base.
            log.warn("NovaSend : statut indisponible (ref={}) : {}", reference, e.getMessage());
            return new Statut(null, Etat.INJOIGNABLE);
        }
    }

    /** Nature du résultat d'une consultation de statut. */
    public enum Etat { VERDICT, INTROUVABLE, INJOIGNABLE }

    /** Résultat d'une consultation : un verdict exploitable, ou l'absence de verdict. */
    public record Statut(Reponse reponse, Etat etat) {
        public boolean aUnVerdict() {
            return etat == Etat.VERDICT && reponse != null;
        }
    }

    /** Parsing tolérant : on ne lit que les champs utiles, les ajouts d'API n'ont pas d'impact. */
    private Reponse lire(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = objectMapper.readTree(json);
            JsonNode failure = n.path("failure");
            return new Reponse(
                    n.path("id").asText(null),
                    n.path("reference").asText(null),
                    n.path("status").asText(""),
                    n.path("confirmationStatus").asText(""),
                    n.path("paymentUrl").asText(null),
                    n.path("amount").asLong(0),
                    n.path("currency").asText(""),
                    failure.isMissingNode() || failure.isNull() ? null : failure.toString());
        } catch (Exception e) {
            log.warn("NovaSend : réponse illisible : {}", e.getMessage());
            return null;
        }
    }

    /**
     * Message destiné à l'ADMINISTRATEUR de l'établissement (jamais au grand public) : il doit
     * être assez précis pour orienter la correction, sans exposer de secret.
     */
    private String messageSelonStatut(int statut, String corps) {
        String detail = extraireMessage(corps);
        return switch (statut) {
            case 401, 403 -> "Identifiants NovaSend refusés (HTTP " + statut + ")"
                    + (detail.isEmpty() ? "" : " : " + detail)
                    + ". Vérifiez la clé API et la clé secrète, et que l'environnement "
                    + "(sandbox/production) correspond bien à ces clés.";
            case 400 -> "Demande refusée par NovaSend"
                    + (detail.isEmpty() ? " : vérifiez le montant et le numéro." : " : " + detail);
            case 404 -> "Ressource introuvable chez NovaSend"
                    + (detail.isEmpty() ? " (opérateur ou pays non reconnu)." : " : " + detail);
            case 409 -> "Cette transaction a déjà été traitée par NovaSend.";
            default -> "Le service de paiement est momentanément indisponible (HTTP " + statut + ").";
        };
    }

    /** Récupère le libellé d'erreur renvoyé par le fournisseur, s'il est exploitable. */
    private String extraireMessage(String corps) {
        if (corps == null || corps.isBlank()) return "";
        try {
            JsonNode n = objectMapper.readTree(corps);
            for (String cle : new String[]{"message", "error", "code", "detail"}) {
                String v = n.path(cle).asText("");
                if (!v.isBlank()) return v;
            }
        } catch (Exception ignore) { /* corps non JSON */ }
        return corps.length() > 160 ? corps.substring(0, 160) : corps;
    }

    /** Réponse NovaSend normalisée (champs réellement exploités). */
    public record Reponse(String id, String reference, String status, String confirmationStatus,
                          String paymentUrl, long amount, String currency, String failure) {

        /**
         * Paiement effectivement encaissé.
         * NB : la documentation NovaSend emploie DEUX vocabulaires — l'API de statut annonce
         * « processed », le webhook annonce « success ». On accepte les deux, sinon un paiement
         * réussi resterait éternellement « en cours » et ne serait jamais crédité.
         */
        public boolean estPaye() {
            return "processed".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status)
                    || "succeeded".equalsIgnoreCase(status);
        }

        /** Échec définitif : inutile de continuer à sonder. */
        public boolean estEchoue() {
            return "failed".equalsIgnoreCase(status) || "failure".equalsIgnoreCase(status)
                    || "expired".equalsIgnoreCase(status)
                    || "cancelled".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status)
                    || "declined".equalsIgnoreCase(confirmationStatus);
        }
    }

    /** Erreur d'appel à NovaSend, avec un message présentable à l'utilisateur. */
    public static class NovaSendException extends RuntimeException {
        public NovaSendException(String message) {
            super(message);
        }
    }
}
