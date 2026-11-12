package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.PaiementIntent;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.StatutIntent;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.PaiementIntentRepository;
import ci.esatic.sigep.repository.PaiementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.tenant.plan.PlanService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le parcours complet d'un paiement Jèko, face à un faux serveur Jèko : la page propose Jèko,
 * l'administrateur part sur la page de paiement, revient, et l'abonnement n'est prolongé qu'après
 * lecture de l'état chez Jèko.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaiementJekoWebTest {

    private static final HttpServer JEKO;
    /** État que le faux Jèko renvoie pour la demande pr-1 ; « pending » tant que rien n'est payé. */
    private static final AtomicReference<String> ETAT = new AtomicReference<>("{\"id\":\"pr-1\",\"status\":\"pending\"}");
    private static final AtomicReference<String> DERNIERE_CREATION = new AtomicReference<>();

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
                DERNIERE_CREATION.set(corps);
                reponse = "{\"id\":\"pr-1\",\"status\":\"pending\",\"redirectUrl\":\"https://pay.jeko.africa/pay_request/pr/pr-1\"}";
            } else {
                reponse = ETAT.get();
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
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private PaiementIntentRepository intentRepository;
    @Autowired private PaiementRepository paiementRepository;
    @Autowired private PlanService planService;

    private Etablissement ecole;
    private User admin;

    @BeforeEach
    void preparer() {
        long n = System.nanoTime();
        ETAT.set("{\"id\":\"pr-1\",\"status\":\"pending\"}");
        ecole = etablissementRepository.save(Etablissement.builder().nom("Ecole Jeko " + n).slug("jeko-" + n)
                .plan(Plan.FREE).kioskKey("cle-" + n).build());
        admin = compte(ecole, n);
    }

    private User compte(Etablissement e, long n) {
        Role role = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        return userRepository.save(User.builder().email("admin-jeko-" + n + "@test.local").password("x")
                .roles(new HashSet<>(Set.of(role))).etablissement(e).build());
    }

    @Test
    void laPageDAbonnement_proposeJeko_enEnvoiClassique() throws Exception {
        mockMvc.perform(get("/admin/abonnement").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/admin/abonnement/jeko\"")))
                .andExpect(content().string(containsString("data-no-boost")))
                .andExpect(content().string(containsString("Djamo")));
    }

    @Test
    void parcoursComplet_departVersJeko_retour_puisCreditApresLecture() throws Exception {
        // 1. Départ : la demande est créée chez Jèko et l'administrateur part sur sa page.
        mockMvc.perform(post("/admin/abonnement/jeko").with(user(admin)).with(csrf())
                        .param("plan", "PRO").param("mois", "1").param("moyen", "wave"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://pay.jeko.africa/pay_request/pr/pr-1"));

        PaiementIntent intent = intentRepository.findByEtablissementIdOrderByDateCreationDesc(ecole.getId()).get(0);
        assertThat(intent.getReference()).startsWith("JK-");
        assertThat(intent.getNovasendId()).isEqualTo("pr-1");
        long attendu = planService.prixMensuel(Plan.PRO);
        assertThat(DERNIERE_CREATION.get()).contains("\"amountCents\":" + (attendu * 100))
                .contains("\"paymentMethod\":\"wave\"").contains("/admin/abonnement/momo/retour?ref=" + intent.getReference());

        // 2. Retour avant paiement : rien n'est crédité, on attend.
        mockMvc.perform(get("/admin/abonnement/momo/retour").with(user(admin)).param("ref", intent.getReference()))
                .andExpect(redirectedUrl("/admin/abonnement/momo/attente?ref=" + intent.getReference()));
        assertThat(paiementRepository.findByEtablissementIdOrderByDatePaiementDesc(ecole.getId())).isEmpty();

        // 3. Jèko confirme : l'abonnement est prolongé, une seule fois.
        ETAT.set("{\"id\":\"pr-1\",\"status\":\"success\",\"transaction\":{\"amount\":{\"amount\":"
                + (attendu * 100) + ",\"currency\":\"XOF\"},\"status\":\"success\"}}");
        mockMvc.perform(get("/admin/abonnement/momo/retour").with(user(admin)).param("ref", intent.getReference()))
                .andExpect(redirectedUrl("/admin/abonnement"))
                .andExpect(flash().attribute("momoConfirme", true));
        mockMvc.perform(get("/admin/abonnement/momo/retour").with(user(admin)).param("ref", intent.getReference()))
                .andExpect(redirectedUrl("/admin/abonnement"));

        assertThat(intentRepository.findByReference(intent.getReference()).orElseThrow().getStatut())
                .isEqualTo(StatutIntent.REUSSI);
        assertThat(paiementRepository.findByEtablissementIdOrderByDatePaiementDesc(ecole.getId())).hasSize(1);
        Etablissement apres = etablissementRepository.findById(ecole.getId()).orElseThrow();
        assertThat(apres.getPlan()).isEqualTo(Plan.PRO);
        assertThat(apres.getDateExpiration()).isNotNull();
    }

    @Test
    void unAutreEtablissement_nePeutPasSuivreCePaiement() throws Exception {
        mockMvc.perform(post("/admin/abonnement/jeko").with(user(admin)).with(csrf())
                        .param("plan", "PRO").param("mois", "1").param("moyen", "orange"))
                .andExpect(status().is3xxRedirection());
        String reference = intentRepository.findByEtablissementIdOrderByDateCreationDesc(ecole.getId()).get(0).getReference();

        long n = System.nanoTime();
        Etablissement autre = etablissementRepository.save(Etablissement.builder().nom("Autre " + n).slug("autre-" + n)
                .plan(Plan.FREE).kioskKey("cle-autre-" + n).build());
        mockMvc.perform(get("/admin/abonnement/momo/retour").with(user(compte(autre, n))).param("ref", reference))
                .andExpect(redirectedUrl("/admin/abonnement"))
                .andExpect(flash().attribute("erreurAbo", "Transaction introuvable."));
    }

    @Test
    void unMoyenInconnu_estRefuse_sansAppelerJeko() throws Exception {
        DERNIERE_CREATION.set(null);
        mockMvc.perform(post("/admin/abonnement/jeko").with(user(admin)).with(csrf())
                        .param("plan", "PRO").param("mois", "1").param("moyen", "bitcoin"))
                .andExpect(redirectedUrl("/admin/abonnement"))
                .andExpect(flash().attributeExists("erreurAbo"));
        assertThat(DERNIERE_CREATION.get()).isNull();
    }
}
