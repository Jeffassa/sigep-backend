package ci.esatic.sigep.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Client de l'API Jèko (« Partner API ») : encaissement Mobile Money par page de paiement hébergée.
 *
 * <p><b>Parcours.</b> SIGEP crée une demande de paiement ({@code POST /partner_api/payment_requests},
 * type {@code redirect}) ; Jèko renvoie l'adresse de sa page de paiement, où l'administrateur
 * valide avec Wave, Orange Money, MTN, Moov ou Djamo ; il revient ensuite sur SIGEP.
 *
 * <p><b>Source de vérité.</b> Ni le retour du navigateur ni le webhook ne créditent : seul l'état
 * lu chez Jèko ({@code GET /partner_api/payment_requests/{id}}) fait foi. Le webhook ne sert qu'à
 * aller plus vite — et Jèko n'en envoie aucun pour un paiement échoué, d'où le relanceur.
 *
 * <p><b>Attention : Jèko n'a pas de bac à sable.</b> Toute demande créée avec ces clés porte sur
 * de l'argent réel.
 *
 * <p>Authentification : en-têtes {@code X-API-KEY} (la clé) et {@code X-API-KEY-ID} (son
 * identifiant), créés dans le tableau de bord Jèko Business, Paramètres → API &amp; Webhooks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JekoService {

    /** Moyens de paiement acceptés par Jèko pour une page hébergée. */
    public static final Set<String> MOYENS = Set.of("wave", "orange", "mtn", "moov", "djamo");

    /** Préfixe des références SIGEP envoyées à Jèko : distingue ces paiements des autres. */
    public static final String PREFIXE_REFERENCE = "JK-";

    private final ObjectMapper objectMapper;

    @Value("${app.jeko.enabled:false}")
    private boolean enabled;

    @Value("${app.jeko.base-url:https://api.jeko.africa}")
    private String baseUrl;

    @Value("${app.jeko.api-key:}")
    private String apiKey;

    @Value("${app.jeko.api-key-id:}")
    private String apiKeyId;

    /** Magasin Jèko qui reçoit les paiements (page Magasins du tableau de bord). */
    @Value("${app.jeko.store-id:}")
    private String storeId;

    public boolean isEnabled() {
        return enabled && rempli(apiKey) && rempli(apiKeyId) && rempli(storeId);
    }

    public boolean moyenSupporte(String moyen) {
        return moyen != null && MOYENS.contains(moyen.trim().toLowerCase());
    }

    private static boolean rempli(String v) {
        return v != null && !v.isBlank();
    }

    /** Construit une seule fois : un client par appel créerait un pool de connexions par requête. */
    private volatile RestClient client;

    /**
     * Délais courts et obligatoires : un appel qui pend immobiliserait le thread appelant, et sous
     * le relanceur périodique cela suffirait à épuiser les connexions de l'application.
     */
    private RestClient client() {
        RestClient local = client;
        if (local != null) return local;
        synchronized (this) {
            if (client != null) return client;
            var http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3))
                    .build();
            var fabrique = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
            fabrique.setReadTimeout(java.time.Duration.ofSeconds(10));
            // Les valeurs sont nettoyées : un espace ou un retour à la ligne collé avec la clé
            // produirait un refus impossible à distinguer d'une mauvaise clé.
            client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(fabrique)
                    .defaultHeader("X-API-KEY", apiKey.trim())
                    .defaultHeader("X-API-KEY-ID", apiKeyId.trim())
                    .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .build();
            return client;
        }
    }

    /**
     * Crée la demande de paiement chez Jèko.
     *
     * @param reference référence SIGEP unique (5 à 100 caractères) — Jèko refuse un doublon.
     * @param montant   montant en unité de la devise (francs CFA) ; Jèko l'attend en centimes.
     * @param moyen     wave, orange, mtn, moov ou djamo.
     * @param retour    adresse HTTPS où Jèko renvoie l'administrateur, succès comme échec.
     * @throws JekoException avec un message présentable à l'administrateur.
     */
    public Demande creerDemande(String reference, long montant, String devise, String moyen, String retour) {
        Map<String, Object> donnees = new LinkedHashMap<>();
        donnees.put("paymentMethod", moyen.trim().toLowerCase());
        donnees.put("successUrl", retour);
        donnees.put("errorUrl", retour);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", "redirect");
        details.put("data", donnees);

        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("storeId", storeId.trim());
        corps.put("amountCents", Math.multiplyExact(montant, 100L));
        corps.put("currency", devise.toUpperCase());
        corps.put("reference", reference);
        corps.put("paymentDetails", details);

        try {
            String json = client().post()
                    .uri("/partner_api/payment_requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corps)
                    .retrieve()
                    .body(String.class);
            Demande d = lireDemande(json);
            if (d == null || d.id() == null) {
                throw new JekoException("Réponse inattendue de Jèko : le paiement n'a pas pu être préparé.");
            }
            return d;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            String reponse = e.getResponseBodyAsString();
            log.warn("Jèko : création refusée (ref={}) HTTP {} — {}", reference, e.getStatusCode().value(),
                    reponse == null ? "" : reponse.substring(0, Math.min(reponse.length(), 400)));
            throw new JekoException(messageSelonErreur(e.getStatusCode().value(), reponse));
        } catch (JekoException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Jèko : création impossible (ref={}) : {}", reference, e.toString());
            throw new JekoException("Le service de paiement est momentanément indisponible. Réessayez dans un instant.");
        }
    }

    /**
     * Lit l'état d'une demande chez Jèko. Distingue trois cas : un verdict, une demande inconnue de
     * Jèko, et l'absence de réponse — qui ne doit jamais être prise pour un échec.
     */
    public Lecture lire(String idDemande) {
        if (idDemande == null || idDemande.isBlank()) {
            return new Lecture(Etat.INTROUVABLE, null);
        }
        try {
            String json = client().get()
                    .uri("/partner_api/payment_requests/{id}", idDemande)
                    .retrieve()
                    .body(String.class);
            Demande d = lireDemande(json);
            return d == null ? new Lecture(Etat.INJOIGNABLE, null) : new Lecture(Etat.VERDICT, d);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.warn("Jèko : demande introuvable (id={})", idDemande);
            return new Lecture(Etat.INTROUVABLE, null);
        } catch (Exception e) {
            log.warn("Jèko : état indisponible (id={}) : {}", idDemande, e.getMessage());
            return new Lecture(Etat.INJOIGNABLE, null);
        }
    }

    /**
     * L'adresse de la page de paiement ne vient pas de nous : on n'y envoie l'administrateur que si
     * c'est bien une page Jèko en HTTPS. Une réponse altérée ne doit pas devenir une redirection
     * vers un site tiers.
     */
    public static boolean adresseDePaiementSure(String url) {
        if (url == null) return false;
        try {
            URI u = URI.create(url);
            String hote = u.getHost();
            return "https".equalsIgnoreCase(u.getScheme()) && hote != null
                    && (hote.equalsIgnoreCase("jeko.africa") || hote.toLowerCase().endsWith(".jeko.africa"));
        } catch (Exception e) {
            return false;
        }
    }

    /** Lecture tolérante : seuls les champs utiles sont lus, un ajout côté Jèko est sans effet. */
    private Demande lireDemande(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = objectMapper.readTree(json);
            JsonNode transaction = n.path("transaction");
            JsonNode montant = transaction.path("amount");
            return new Demande(
                    n.path("id").asString(null),
                    n.path("reference").asString(null),
                    n.path("status").asString(""),
                    n.path("redirectUrl").asString(null),
                    montant.path("amount").asLong(0),
                    montant.path("currency").asString(""),
                    n.path("errorReason").isNull() ? null : n.path("errorReason").asString(null));
        } catch (Exception e) {
            log.warn("Jèko : réponse illisible : {}", e.getMessage());
            return null;
        }
    }

    /** Message destiné à l'administrateur : assez précis pour agir, sans aucun secret. */
    private String messageSelonErreur(int statut, String corps) {
        String code = "";
        try {
            if (corps != null && !corps.isBlank()) code = objectMapper.readTree(corps).path("id").asString("");
        } catch (Exception ignore) { /* corps non JSON */ }
        return switch (code) {
            case "monthly_collection_limit_exceeded", "operating_balance_limit_exceeded" ->
                    "Le plafond du compte Jèko est atteint. Augmentez son niveau de vérification dans Jèko Business.";
            case "business_not_enabled_for_api_access" ->
                    "Le compte Jèko n'a pas encore accès à l'API : terminez la vérification du compte dans Jèko Business.";
            case "store_not_found" -> "Le magasin Jèko configuré n'existe pas. Vérifiez JEKO_STORE_ID.";
            case "unsupported_payment_method" -> "Ce moyen de paiement n'est pas proposé par Jèko pour le moment.";
            default -> switch (statut) {
                case 401, 403 -> "Jèko refuse les identifiants (HTTP " + statut + "). Vérifiez la clé API et son identifiant.";
                case 409 -> "Ce paiement a déjà été créé chez Jèko.";
                case 422 -> "Jèko a refusé la demande de paiement (données invalides).";
                default -> "Le service de paiement est momentanément indisponible. Réessayez dans un instant.";
            };
        };
    }

    public enum Etat { VERDICT, INTROUVABLE, INJOIGNABLE }

    /** Résultat d'une lecture : un verdict, ou l'absence de verdict. */
    public record Lecture(Etat etat, Demande demande) {
        public boolean aUnVerdict() {
            return etat == Etat.VERDICT && demande != null;
        }
    }

    /**
     * Une demande de paiement telle que Jèko la décrit. {@code montant} est le montant de la
     * transaction une fois payée, tel que Jèko l'indique.
     */
    public record Demande(String id, String reference, String statut, String redirectUrl,
                          long montant, String devise, String raisonEchec) {
        public boolean estPayee() {
            return "success".equalsIgnoreCase(statut);
        }

        public boolean estEchouee() {
            return "error".equalsIgnoreCase(statut);
        }
    }

    /** Erreur d'appel à Jèko, avec un message présentable à l'administrateur. */
    public static class JekoException extends RuntimeException {
        public JekoException(String message) {
            super(message);
        }
    }
}
