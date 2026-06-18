package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test d'intégration de l'authentification : traverse toute la pile
 * (controller → AuthService → AuthenticationManager → UserDetailsService → H2),
 * sans aucun mock métier. Un vrai utilisateur est persisté avec un mot de passe BCrypt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthIntegrationTest {

    @MockBean private DataInitializer dataInitializer; // on contrôle nous-mêmes les données

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String EMAIL = "admin@esatic.ci";
    private static final String PASSWORD = "Admin@2026";

    @BeforeEach
    void setUp() {
        Role admin = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        if (!userRepository.existsByEmail(EMAIL)) {
            userRepository.save(User.builder()
                    .email(EMAIL)
                    .password(passwordEncoder.encode(PASSWORD))
                    .roles(Set.of(admin))
                    .build());
        }
    }

    @Test
    void login_devraitRetournerUnTokenJwtReel() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.email").value(EMAIL))
                .andExpect(jsonPath("$.data.roles[0]").value("ROLE_ADMIN"));
    }

    @Test
    void login_devraitRetourner401SiMotDePasseIncorrect() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("email", EMAIL, "password", "mauvais"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void endpointProtege_devraitRetourner403SansToken() throws Exception {
        mockMvc.perform(get("/api/enseignants/moi"))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Gating : un enseignant ne peut se connecter qu'une fois validé par l'admin
    // =========================================================================

    @Test
    void login_enseignantEnAttente_devraitRetourner403() throws Exception {
        creerEnseignant("pending@esatic.ci", "ENS-P-1", StatutEnseignant.PENDING);

        String body = objectMapper.writeValueAsString(
                Map.of("email", "pending@esatic.ci", "password", PASSWORD));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void login_enseignantRefuse_devraitRetourner403() throws Exception {
        creerEnseignant("rejected@esatic.ci", "ENS-R-1", StatutEnseignant.REJECTED);

        String body = objectMapper.writeValueAsString(
                Map.of("email", "rejected@esatic.ci", "password", PASSWORD));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void login_enseignantValide_devraitReussir() throws Exception {
        creerEnseignant("valide@esatic.ci", "ENS-V-1", StatutEnseignant.VALIDATED);

        String body = objectMapper.writeValueAsString(
                Map.of("email", "valide@esatic.ci", "password", PASSWORD));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.roles[0]").value("ROLE_ENSEIGNANT"));
    }

    private void creerEnseignant(String email, String matricule, StatutEnseignant statut) {
        Role ens = roleRepository.findByName(ERole.ROLE_ENSEIGNANT)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ENSEIGNANT)));
        User u = userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(PASSWORD))
                .roles(Set.of(ens))
                .build());
        enseignantRepository.save(Enseignant.builder()
                .matricule(matricule).nom("Kone").prenom("Awa")
                .statut(statut).user(u)
                .build());
    }
}
