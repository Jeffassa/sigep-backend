package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Correctifs de l'audit de sécurité du 19 novembre 2026. */
@SpringBootTest(properties = "app.security.login-rate-limit.max-admin-login=1000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DurcissementAuditTest {

    private static final String MDP = "Secret@2026";

    @MockitoBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private User compte;

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        Etablissement ecole = etablissementRepository.save(Etablissement.builder()
                .nom("Ecole").slug("audit-" + n).plan(Plan.ENTERPRISE).build());
        Role role = roleRepository.findByName(ERole.ROLE_ENSEIGNANT)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ENSEIGNANT)));
        compte = userRepository.save(User.builder().email("audit-" + n + "@test.local")
                .password(passwordEncoder.encode(MDP))
                .roles(new HashSet<>(Set.of(role))).etablissement(ecole).build());
        enseignantRepository.save(Enseignant.builder().matricule("A-" + n).nom("Kone").prenom("Awa")
                .statut(StatutEnseignant.VALIDATED).user(compte).etablissementId(ecole.getId()).build());
    }

    private ResultActions connexion(String mdp) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", compte.getEmail(), "password", mdp))));
    }

    @Test
    void apresDixEchecs_memeLeBonMotDePasseEstRefuse() throws Exception {
        for (int i = 0; i < 10; i++) {
            connexion("mauvais-" + i).andExpect(status().isUnauthorized());
        }
        connexion(MDP).andExpect(status().isTooManyRequests());
    }

    @Test
    void uneConnexionReussie_remetLeCompteurAZero() throws Exception {
        for (int i = 0; i < 9; i++) connexion("mauvais-" + i);
        connexion(MDP).andExpect(status().isOk());
        for (int i = 0; i < 9; i++) connexion("mauvais-" + i);
        connexion(MDP).andExpect(status().isOk());
    }

    @Test
    void laConsoleAussiEstFreinee() throws Exception {
        String email = "inconnu-" + System.nanoTime() + "@test.local";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/admin-login").with(csrf()).param("username", email).param("password", "x" + i))
                    .andExpect(redirectedUrl("/admin-login?error=true"));
        }
        mockMvc.perform(post("/admin-login").with(csrf()).param("username", email).param("password", "y"))
                .andExpect(redirectedUrl("/admin-login?bloque=1"));
    }

    @Test
    void unCompteDesactive_perdSonJetonToutDeSuite() throws Exception {
        String jeton = jwtService.generateToken(compte);
        mockMvc.perform(get("/api/seances/aujourd-hui").header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk());

        compte.setEnabled(false);
        userRepository.saveAndFlush(compte);

        mockMvc.perform(get("/api/seances/aujourd-hui").header("Authorization", "Bearer " + jeton))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unMotDePasseProvisoire_neDonneAccesQuASonRemplacement() throws Exception {
        compte.setMustChangePassword(true);
        userRepository.saveAndFlush(compte);
        String jeton = jwtService.generateToken(compte);

        mockMvc.perform(get("/api/seances/aujourd-hui").header("Authorization", "Bearer " + jeton))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.code").value("MOT_DE_PASSE_A_CHANGER"));

        mockMvc.perform(post("/api/profil/mot-de-passe").header("Authorization", "Bearer " + jeton)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "ancienMotDePasse", MDP, "nouveauMotDePasse", "Nouveau@2027"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/seances/aujourd-hui").header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk());
    }

    @Test
    void lesMetriquesDeLaPlateforme_neSontPasOuvertesAUnEnseignant() throws Exception {
        String jeton = jwtService.generateToken(compte);
        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + jeton))
                .andExpect(status().isForbidden());
    }
}
