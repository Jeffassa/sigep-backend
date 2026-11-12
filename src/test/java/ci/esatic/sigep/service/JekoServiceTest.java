package ci.esatic.sigep.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le client Jèko face à un faux serveur Jèko local : ce qu'il envoie, et comment il lit les réponses.
 * Jèko n'ayant pas de bac à sable, c'est le seul moyen de l'éprouver sans argent réel.
 */
class JekoServiceTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private HttpServer serveur;
    private JekoService jeko;

    /** Réponses programmées : chemin -> { code HTTP, corps }. */
    private final Map<String, Object[]> reponses = new ConcurrentHashMap<>();
    private volatile String dernierCorps;
    private volatile Map<String, String> derniersEntetes;

    @BeforeEach
    void demarrer() throws Exception {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.createContext("/", echange -> {
            dernierCorps = new String(echange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            derniersEntetes = Map.of(
                    "X-API-KEY", String.valueOf(echange.getRequestHeaders().getFirst("X-API-KEY")),
                    "X-API-KEY-ID", String.valueOf(echange.getRequestHeaders().getFirst("X-API-KEY-ID")));
            Object[] r = reponses.getOrDefault(echange.getRequestMethod() + " " + echange.getRequestURI().getPath(),
                    new Object[] {500, "{}"});
            byte[] octets = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
            echange.getResponseHeaders().add("Content-Type", "application/json");
            echange.sendResponseHeaders((Integer) r[0], octets.length);
            try (OutputStream o = echange.getResponseBody()) { o.write(octets); }
        });
        serveur.start();

        jeko = new JekoService(json);
        ReflectionTestUtils.setField(jeko, "enabled", true);
        ReflectionTestUtils.setField(jeko, "baseUrl", "http://127.0.0.1:" + serveur.getAddress().getPort());
        ReflectionTestUtils.setField(jeko, "apiKey", " cle-secrete \n");
        ReflectionTestUtils.setField(jeko, "apiKeyId", "id-cle");
        ReflectionTestUtils.setField(jeko, "storeId", "magasin-1");
    }

    @AfterEach
    void arreter() {
        serveur.stop(0);
    }

    @Test
    void creerDemande_envoieLeMontantEnCentimesEtLesDeuxCles() throws Exception {
        reponses.put("POST /partner_api/payment_requests", new Object[] {200,
                "{\"id\":\"pr-1\",\"reference\":\"JK-abc\",\"status\":\"pending\",\"redirectUrl\":\"https://pay.jeko.africa/pay_request/pr/pr-1\"}"});

        JekoService.Demande d = jeko.creerDemande("JK-abc", 13000, "xof", "Wave", "https://sigep.store/retour");

        assertThat(d.id()).isEqualTo("pr-1");
        assertThat(d.redirectUrl()).isEqualTo("https://pay.jeko.africa/pay_request/pr/pr-1");
        assertThat(derniersEntetes).containsEntry("X-API-KEY", "cle-secrete").containsEntry("X-API-KEY-ID", "id-cle");
        JsonNode corps = json.readTree(dernierCorps);
        assertThat(corps.path("storeId").asString()).isEqualTo("magasin-1");
        assertThat(corps.path("amountCents").asLong()).isEqualTo(1_300_000L);   // 13 000 F en centimes
        assertThat(corps.path("currency").asString()).isEqualTo("XOF");
        assertThat(corps.path("reference").asString()).isEqualTo("JK-abc");
        assertThat(corps.path("paymentDetails").path("type").asString()).isEqualTo("redirect");
        JsonNode donnees = corps.path("paymentDetails").path("data");
        assertThat(donnees.path("paymentMethod").asString()).isEqualTo("wave");
        assertThat(donnees.path("successUrl").asString()).isEqualTo("https://sigep.store/retour");
        assertThat(donnees.path("errorUrl").asString()).isEqualTo("https://sigep.store/retour");
    }

    @Test
    void unRefusDeJeko_devientUnMessageUtile() {
        reponses.put("POST /partner_api/payment_requests", new Object[] {403,
                "{\"id\":\"business_not_enabled_for_api_access\",\"message\":\"not enabled\"}"});
        assertThatThrownBy(() -> jeko.creerDemande("JK-abc", 13000, "XOF", "wave", "https://sigep.store/r"))
                .isInstanceOf(JekoService.JekoException.class)
                .hasMessageContaining("vérification du compte");

        reponses.put("POST /partner_api/payment_requests", new Object[] {400,
                "{\"id\":\"monthly_collection_limit_exceeded\",\"message\":\"limit\"}"});
        assertThatThrownBy(() -> jeko.creerDemande("JK-abd", 13000, "XOF", "wave", "https://sigep.store/r"))
                .hasMessageContaining("plafond");
    }

    @Test
    void lire_rendUnVerdictPaye() {
        reponses.put("GET /partner_api/payment_requests/pr-1", new Object[] {200,
                "{\"id\":\"pr-1\",\"reference\":\"JK-abc\",\"status\":\"success\",\"transaction\":{\"id\":\"txn_1\","
                        + "\"amount\":{\"amount\":1300000,\"currency\":\"XOF\"},\"status\":\"success\"}}"});

        JekoService.Lecture l = jeko.lire("pr-1");

        assertThat(l.aUnVerdict()).isTrue();
        assertThat(l.demande().estPayee()).isTrue();
        assertThat(l.demande().montant()).isEqualTo(1_300_000L);
        assertThat(l.demande().devise()).isEqualTo("XOF");
    }

    @Test
    void lire_distingueEchecAttenteInconnueEtPanne() {
        reponses.put("GET /partner_api/payment_requests/pr-e", new Object[] {200,
                "{\"id\":\"pr-e\",\"status\":\"error\",\"errorReason\":\"payment_failed\"}"});
        reponses.put("GET /partner_api/payment_requests/pr-p", new Object[] {200, "{\"id\":\"pr-p\",\"status\":\"pending\"}"});
        reponses.put("GET /partner_api/payment_requests/pr-x", new Object[] {404,
                "{\"id\":\"payment_request_not_found\"}"});

        JekoService.Lecture echec = jeko.lire("pr-e");
        assertThat(echec.demande().estEchouee()).isTrue();
        assertThat(echec.demande().raisonEchec()).isEqualTo("payment_failed");

        JekoService.Lecture attente = jeko.lire("pr-p");
        assertThat(attente.demande().estPayee()).isFalse();
        assertThat(attente.demande().estEchouee()).isFalse();

        assertThat(jeko.lire("pr-x").etat()).isEqualTo(JekoService.Etat.INTROUVABLE);
        // Une panne n'est pas un verdict : on ne sait pas, on ne clôt rien.
        assertThat(jeko.lire("pr-inconnu-du-faux-serveur").etat()).isEqualTo(JekoService.Etat.INJOIGNABLE);
        assertThat(jeko.lire(null).etat()).isEqualTo(JekoService.Etat.INTROUVABLE);
    }

    @Test
    void seuleUnePageJekoEnHttpsEstUneDestinationSure() {
        assertThat(JekoService.adresseDePaiementSure("https://pay.jeko.africa/pay_request/pr/1")).isTrue();
        assertThat(JekoService.adresseDePaiementSure("https://link.jeko.africa/x")).isTrue();
        assertThat(JekoService.adresseDePaiementSure("http://pay.jeko.africa/x")).isFalse();
        assertThat(JekoService.adresseDePaiementSure("https://jeko.africa.pirate.com/x")).isFalse();
        assertThat(JekoService.adresseDePaiementSure("javascript:alert(1)")).isFalse();
        assertThat(JekoService.adresseDePaiementSure(null)).isFalse();
    }

    @Test
    void sansCleOuSansMagasin_JekoNEstPasActif() {
        ReflectionTestUtils.setField(jeko, "storeId", "");
        assertThat(jeko.isEnabled()).isFalse();
    }
}
