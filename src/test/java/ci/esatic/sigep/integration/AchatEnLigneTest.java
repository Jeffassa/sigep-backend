package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.PaiementIntent;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.StatutEtablissement;
import ci.esatic.sigep.entity.StatutIntent;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.PaiementIntentRepository;
import ci.esatic.sigep.repository.PaiementRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.tenant.plan.PlanService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L'achat en libre-service depuis le bordereau de l'accueil : un visiteur choisit un plan payant,
 * crée son espace, paie chez Jèko et se retrouve avec un espace actif, sans que personne
 * n'intervienne. Jèko est remplacé par un faux serveur local.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AchatEnLigneTest {

    private static final HttpServer JEKO;
    private static final AtomicInteger NUMERO = new AtomicInteger();
    /** État renvoyé par le faux Jèko pour chaque demande ; « pending » par défaut. */
    private static final Map<String, String> ETATS = new ConcurrentHashMap<>();
    private static final Map<String, String> CREATIONS = new ConcurrentHashMap<>();

    static {
        try {
            JEKO = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        JEKO.createContext("/partner_api/payment_requests", echange -> {
            String corps = new String(echange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reponse;
            if ("POST".equals(echange.getRequestMethod())) {
                String id = "pr-" + NUMERO.incrementAndGet();
                CREATIONS.put(id, corps);
                reponse = "{\"id\":\"" + id + "\",\"status\":\"pending\",\"redirectUrl\":\"https://pay.jeko.africa/pay_request/pr/" + id + "\"}";
            } else {
                String id = echange.getRequestURI().getPath().replaceAll(".*/", "");
                reponse = ETATS.getOrDefault(id, "{\"id\":\"" + id + "\",\"status\":\"pending\"}");
            }
            byte[] octets = reponse.getBytes(StandardCharsets.UTF_8);
            echange.getResponseHeaders().add("Content-Type", "application/json");
            echange.sendResponseHeaders(200, octets.length);
            try (OutputStream o = echange.getResponseBody()) { o.write(octets); }
        });
        JEKO.start();
    }

    @DynamicPropertySource
    static void jeko(DynamicPropertyRegistry r) {
        r.add("app.jeko.enabled", () -> "true");
        r.add("app.jeko.base-url", () -> "http://127.0.0.1:" + JEKO.getAddress().getPort());
        r.add("app.jeko.api-key", () -> "cle-de-test");
        r.add("app.jeko.api-key-id", () -> "id-de-test");
        r.add("app.jeko.store-id", () -> "magasin-de-test");
    }

    @AfterAll
    static void arreter() {
        JEKO.stop(0);
    }

    @MockitoBean private DataInitializer dataInitializer;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private PaiementIntentRepository intentRepository;
    @Autowired private PaiementRepository paiementRepository;
    @Autowired private PlanService planService;

    @Autowired private ci.esatic.sigep.repository.RoleRepository roleRepository;

    @org.junit.jupiter.api.BeforeEach
    void roleAdmin() {
        // L'initialisation des données est remplacée : le rôle exigé par l'inscription est créé ici.
        roleRepository.findByName(ci.esatic.sigep.entity.ERole.ROLE_ADMIN).orElseGet(() ->
                roleRepository.save(new ci.esatic.sigep.entity.Role(null, ci.esatic.sigep.entity.ERole.ROLE_ADMIN)));
    }

    private JsonNode inscrire(String plan, Integer mois, String moyen) throws Exception {
        long n = System.nanoTime();
        String corps = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>(Map.of(
                "nomEtablissement", "Lycée Achat " + n,
                "adminEmail", "achat-" + n + "@test.local",
                "adminPassword", "Secret@2026",
                "adminPrenom", "Awa",
                "adminNom", "Koné",
                "plan", plan == null ? "" : plan,
                "mois", mois == null ? 1 : mois,
                "moyen", moyen == null ? "" : moyen)));
        String reponse = mockMvc.perform(post("/api/saas/etablissements")
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(reponse);
    }

    private Etablissement etablissementDe(JsonNode reponse) {
        String email = reponse.path("data").path("email").asString();
        return userRepository.findByEmail(email).orElseThrow().getEtablissement();
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void leBordereau_meneAuPaiement_etNePrometQueCeQuiExiste() throws Exception {
        String accueil = page("/");
        assertThat(accueil)
                .contains("href=\"/inscription?plan=PRO\"")
                .contains("href=\"/inscription?plan=ENTERPRISE\"")
                .contains("Paiement Mobile Money sécurisé par Jèko")
                .doesNotContain("SSO")
                .doesNotContain("Analyse par IA")
                .doesNotContain("Essayer Pro");

        String inscription = page("/inscription?plan=ENTERPRISE");
        assertThat(inscription).contains("Créer mon espace et payer").contains("data-plan=\"ENTERPRISE\"")
                .contains("Plan <span>Enterprise</span>");
        // Un plan inconnu ramène à l'inscription gratuite, sans erreur.
        assertThat(page("/inscription?plan=DIAMANT")).contains("Commencez gratuitement");
    }

    @Test
    void parcoursComplet_inscription_paiement_espaceActiveSansIntervention() throws Exception {
        JsonNode r = inscrire("PRO", 3, "orange");

        // 1. La réponse envoie directement sur la page de Jèko.
        String reference = r.path("data").path("reference").asString();
        assertThat(reference).startsWith("JK-");
        assertThat(r.path("data").path("paiementUrl").asString()).startsWith("https://pay.jeko.africa/");
        Etablissement e = etablissementDe(r);
        assertThat(e.getStatut()).isEqualTo(StatutEtablissement.EN_ATTENTE);

        PaiementIntent intent = intentRepository.findByReference(reference).orElseThrow();
        long attendu = 3 * planService.prixMensuel(Plan.PRO);
        assertThat(intent.getMontantAttendu()).isEqualTo(attendu);
        assertThat(CREATIONS.get(intent.getNovasendId()))
                .contains("\"amountCents\":" + (attendu * 100))
                .contains("\"paymentMethod\":\"orange\"")
                .contains("/inscription/paiement?ref=" + reference);

        // 2. Retour avant paiement : on attend, rien n'est activé.
        assertThat(page("/inscription/paiement?ref=" + reference)).contains("Paiement en attente.");
        assertThat(etablissementRepository.findById(e.getId()).orElseThrow().getStatut())
                .isEqualTo(StatutEtablissement.EN_ATTENTE);

        // 3. Jèko confirme : l'espace est activé en Pro pour trois mois, sans examen.
        ETATS.put(intent.getNovasendId(), "{\"id\":\"" + intent.getNovasendId() + "\",\"status\":\"success\","
                + "\"transaction\":{\"amount\":{\"amount\":" + (attendu * 100) + ",\"currency\":\"XOF\"}}}");
        Thread.sleep(4_100);   // la page relit Jèko au plus toutes les quatre secondes
        assertThat(page("/inscription/paiement?ref=" + reference)).contains("Votre espace est activé.")
                .contains("href=\"/admin-login\"");

        Etablissement apres = etablissementRepository.findById(e.getId()).orElseThrow();
        assertThat(apres.getStatut()).isEqualTo(StatutEtablissement.VALIDE);
        assertThat(apres.getPlan()).isEqualTo(Plan.PRO);
        assertThat(apres.getDateExpiration()).isAfter(java.time.LocalDate.now().plusMonths(2));
        assertThat(paiementRepository.findByEtablissementIdOrderByDatePaiementDesc(e.getId())).hasSize(1);
    }

    @Test
    void paiementEchoue_leClientReessaieSeul_avecUnAutreMoyen() throws Exception {
        JsonNode r = inscrire("PRO", 1, "wave");
        String reference = r.path("data").path("reference").asString();
        PaiementIntent intent = intentRepository.findByReference(reference).orElseThrow();
        ETATS.put(intent.getNovasendId(), "{\"id\":\"" + intent.getNovasendId() + "\",\"status\":\"error\",\"errorReason\":\"payment_failed\"}");

        String suivi = page("/inscription/paiement?ref=" + reference);
        assertThat(suivi).contains("Le paiement n'a pas abouti.").contains("Payer à nouveau");
        assertThat(intentRepository.findByReference(reference).orElseThrow().getStatut()).isEqualTo(StatutIntent.ECHOUE);

        String destination = mockMvc.perform(post("/inscription/paiement/reessayer").with(csrf())
                        .param("ref", reference).param("moyen", "mtn"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(destination).startsWith("https://pay.jeko.africa/");

        PaiementIntent nouvelle = intentRepository
                .findByEtablissementIdOrderByDateCreationDesc(intent.getEtablissementId()).get(0);
        assertThat(nouvelle.getReference()).isNotEqualTo(reference);
        assertThat(nouvelle.getProvider()).isEqualTo("MTN");
        assertThat(nouvelle.getStatut()).isEqualTo(StatutIntent.EN_COURS);
    }

    @Test
    void unEspaceDejaActive_neSePayePasDeuxFoisParLaPagePublique() throws Exception {
        JsonNode r = inscrire("PRO", 1, "wave");
        String reference = r.path("data").path("reference").asString();
        PaiementIntent intent = intentRepository.findByReference(reference).orElseThrow();
        long attendu = planService.prixMensuel(Plan.PRO);
        ETATS.put(intent.getNovasendId(), "{\"id\":\"" + intent.getNovasendId() + "\",\"status\":\"success\","
                + "\"transaction\":{\"amount\":{\"amount\":" + (attendu * 100) + ",\"currency\":\"XOF\"}}}");
        page("/inscription/paiement?ref=" + reference);

        mockMvc.perform(post("/inscription/paiement/reessayer").with(csrf()).param("ref", reference).param("moyen", "mtn"))
                .andExpect(redirectedUrl("/inscription/paiement?ref=" + reference));
        assertThat(intentRepository.findByEtablissementIdOrderByDateCreationDesc(intent.getEtablissementId())).hasSize(1);
    }

    @Test
    void uneReferenceInconnue_ouDeLEspaceAdministration_nOuvreRien() throws Exception {
        mockMvc.perform(get("/inscription/paiement").param("ref", "JK-inexistante"))
                .andExpect(redirectedUrl("/inscription"));
        mockMvc.perform(get("/inscription/paiement").param("ref", "0b4a-paiement-novasend"))
                .andExpect(redirectedUrl("/inscription"));
    }

    @Test
    void linscriptionGratuite_resteExamineeParLaPlateforme() throws Exception {
        JsonNode r = inscrire(null, null, null);
        assertThat(r.path("data").has("reference")).isFalse();
        assertThat(r.path("message").asString()).contains("en cours d'analyse");
        assertThat(etablissementDe(r).getStatut()).isEqualTo(StatutEtablissement.EN_ATTENTE);
        assertThat(intentRepository.findByEtablissementIdOrderByDateCreationDesc(etablissementDe(r).getId())).isEmpty();
    }
}
