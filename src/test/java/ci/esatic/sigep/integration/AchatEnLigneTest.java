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

    private JsonNode inscrire(String plan) throws Exception {
        long n = System.nanoTime();
        String corps = objectMapper.writeValueAsString(Map.of(
                "nomEtablissement", "Lycée Achat " + n,
                "adminEmail", "achat-" + n + "@test.local",
                "adminPassword", "Secret@2026",
                "adminPrenom", "Awa",
                "adminNom", "Koné",
                "plan", plan == null ? "" : plan));
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

    /** Le jeton porté par l'adresse de la page de paiement. */
    private static String jetonDe(String pagePaiement) {
        String apres = pagePaiement.substring(pagePaiement.indexOf("jeton=") + 6);
        return java.net.URLDecoder.decode(apres.split("&")[0], StandardCharsets.UTF_8);
    }

    /** Envoie le formulaire de la page de paiement ; rend l'adresse de redirection. */
    private String payer(String jeton, String plan, int mois, String moyen) throws Exception {
        return mockMvc.perform(post("/inscription/payer").with(csrf())
                        .param("jeton", jeton).param("plan", plan)
                        .param("mois", String.valueOf(mois)).param("moyen", moyen))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    private void jekoConfirme(PaiementIntent intent) {
        ETATS.put(intent.getNovasendId(), "{\"id\":\"" + intent.getNovasendId() + "\",\"status\":\"success\","
                + "\"transaction\":{\"amount\":{\"amount\":" + (intent.getMontantAttendu() * 100) + ",\"currency\":\"XOF\"}}}");
    }

    @Test
    void leBordereau_meneALInscription_quiNeParlePasDePrix() throws Exception {
        String accueil = page("/");
        assertThat(accueil)
                .contains("href=\"/inscription?plan=PRO\"")
                .contains("href=\"/inscription?plan=ENTERPRISE\"")
                .doesNotContain("Paiement Mobile Money sécurisé par Jèko")
                .doesNotContain("SSO")
                .doesNotContain("Analyse par IA")
                .doesNotContain("Essayer Pro");

        String inscription = page("/inscription?plan=ENTERPRISE");
        assertThat(inscription).contains("Créer mon espace et continuer").contains("data-plan=\"ENTERPRISE\"");
        // Ni tarif, ni durée, ni moyen de paiement sur l'inscription : c'est l'étape suivante.
        assertThat(inscription).doesNotContain("96 000").doesNotContain("FCFA")
                .doesNotContain("Durée").doesNotContain("Payer avec");
        // Un plan inconnu ramène à l'inscription gratuite, sans erreur.
        assertThat(page("/inscription?plan=DIAMANT")).doesNotContain("data-plan=\"").contains(">Créer mon espace<");
    }

    @Test
    void parcoursComplet_inscription_pageDePaiement_jeko_espaceActive() throws Exception {
        // 1. L'inscription crée l'espace et mène à la page de paiement, sans rien payer encore.
        JsonNode r = inscrire("PRO");
        String pagePaiement = r.path("data").path("paiementPage").asString();
        assertThat(pagePaiement).startsWith("/inscription/payer?jeton=").endsWith("&plan=PRO");
        Etablissement e = etablissementDe(r);
        assertThat(e.getStatut()).isEqualTo(StatutEtablissement.EN_ATTENTE);
        assertThat(intentRepository.findByEtablissementIdOrderByDateCreationDesc(e.getId())).isEmpty();

        // 2. La page de paiement présente les offres, la durée, le moyen et le total.
        String paiement = page(pagePaiement);
        assertThat(paiement).contains("Paiement.").contains("Durée").contains("Payer avec")
                .containsPattern("value=\"PRO\"[^>]*checked").contains("value=\"ENTERPRISE\"").contains("Wave");

        // 3. Le client choisit trois mois en Orange Money et part chez Jèko.
        String destination = payer(jetonDe(pagePaiement), "PRO", 3, "orange");
        assertThat(destination).startsWith("https://pay.jeko.africa/");
        PaiementIntent intent = intentRepository.findByEtablissementIdOrderByDateCreationDesc(e.getId()).get(0);
        long attendu = 3 * planService.prixMensuel(Plan.PRO);
        assertThat(intent.getMontantAttendu()).isEqualTo(attendu);
        assertThat(intent.getProvider()).isEqualTo("ORANGE");
        assertThat(CREATIONS.get(intent.getNovasendId()))
                .contains("\"amountCents\":" + (attendu * 100))
                .contains("/inscription/paiement?ref=" + intent.getReference());

        // 4. Retour avant paiement : on attend.
        assertThat(page("/inscription/paiement?ref=" + intent.getReference())).contains("Paiement en attente.");

        // 5. Jèko confirme : l'espace est activé en Pro pour trois mois, sans examen.
        jekoConfirme(intent);
        Thread.sleep(4_100);   // la page relit Jèko au plus toutes les quatre secondes
        assertThat(page("/inscription/paiement?ref=" + intent.getReference())).contains("Votre espace est activé.");
        Etablissement apres = etablissementRepository.findById(e.getId()).orElseThrow();
        assertThat(apres.getStatut()).isEqualTo(StatutEtablissement.VALIDE);
        assertThat(apres.getPlan()).isEqualTo(Plan.PRO);
        assertThat(apres.getDateExpiration()).isAfter(java.time.LocalDate.now().plusMonths(2));
        assertThat(paiementRepository.findByEtablissementIdOrderByDatePaiementDesc(e.getId())).hasSize(1);

        // 6. La page de paiement ne propose plus de payer : l'espace est actif.
        assertThat(page(pagePaiement)).contains("Votre espace est actif.").doesNotContain("Payer avec");
    }

    @Test
    void apresUnEchec_lePaiementSeRefaitDepuisLaPageDePaiement() throws Exception {
        JsonNode r = inscrire("ENTERPRISE");
        String jeton = jetonDe(r.path("data").path("paiementPage").asString());
        payer(jeton, "ENTERPRISE", 1, "wave");
        PaiementIntent premier = intentRepository.findByEtablissementIdOrderByDateCreationDesc(etablissementDe(r).getId()).get(0);
        ETATS.put(premier.getNovasendId(), "{\"id\":\"" + premier.getNovasendId() + "\",\"status\":\"error\"}");

        String suivi = page("/inscription/paiement?ref=" + premier.getReference());
        assertThat(suivi).contains("Le paiement n'a pas abouti.").contains("Payer à nouveau")
                .contains("href=\"/inscription/payer?jeton=");

        String destination = payer(jeton, "ENTERPRISE", 1, "mtn");
        assertThat(destination).startsWith("https://pay.jeko.africa/");
        PaiementIntent second = intentRepository.findByEtablissementIdOrderByDateCreationDesc(premier.getEtablissementId()).get(0);
        assertThat(second.getReference()).isNotEqualTo(premier.getReference());
        assertThat(second.getProvider()).isEqualTo("MTN");
    }

    @Test
    void leClientPeutChangerDAvis_maisUnDoubleClicNeCreePasDeuxPaiements() throws Exception {
        JsonNode r = inscrire("PRO");
        String jeton = jetonDe(r.path("data").path("paiementPage").asString());
        Long id = etablissementDe(r).getId();

        String premiere = payer(jeton, "PRO", 1, "wave");
        String doubleClic = payer(jeton, "PRO", 1, "wave");
        assertThat(doubleClic).isEqualTo(premiere);
        assertThat(intentRepository.findByEtablissementIdOrderByDateCreationDesc(id)).hasSize(1);

        // Revenu de la page de Jèko, il préfère Djamo et douze mois : une nouvelle demande est créée.
        assertThat(page("/inscription/payer?jeton=" + java.net.URLEncoder.encode(jeton, StandardCharsets.UTF_8)))
                .contains("est déjà commencé");
        String autre = payer(jeton, "PRO", 12, "djamo");
        assertThat(autre).isNotEqualTo(premiere);
        assertThat(intentRepository.findByEtablissementIdOrderByDateCreationDesc(id)).hasSize(2);
    }

    @Test
    void unJetonAltere_ouUneReferenceInconnue_nOuvrentRien() throws Exception {
        JsonNode r = inscrire("PRO");
        String jeton = jetonDe(r.path("data").path("paiementPage").asString());
        String[] parties = jeton.split("\\.");
        String falsifie = (Long.parseLong(parties[0]) + 1) + "." + parties[1] + "." + parties[2];

        mockMvc.perform(get("/inscription/payer").param("jeton", falsifie)).andExpect(redirectedUrl("/inscription"));
        mockMvc.perform(post("/inscription/payer").with(csrf()).param("jeton", falsifie)
                        .param("plan", "PRO").param("mois", "1").param("moyen", "wave"))
                .andExpect(redirectedUrl("/inscription"));
        mockMvc.perform(get("/inscription/paiement").param("ref", "JK-inexistante")).andExpect(redirectedUrl("/inscription"));
        mockMvc.perform(get("/inscription/paiement").param("ref", "0b4a-paiement-novasend")).andExpect(redirectedUrl("/inscription"));
    }

    @Test
    void linscriptionGratuite_resteExamineeParLaPlateforme() throws Exception {
        JsonNode r = inscrire(null);
        assertThat(r.path("data").has("paiementPage")).isFalse();
        assertThat(r.path("message").asString()).contains("en cours d'analyse");
        assertThat(etablissementDe(r).getStatut()).isEqualTo(StatutEtablissement.EN_ATTENTE);
        assertThat(intentRepository.findByEtablissementIdOrderByDateCreationDesc(etablissementDe(r).getId())).isEmpty();
    }
}
