package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test d'intégration du flux refresh token : login → /refresh → rotation,
 * sur toute la pile (controller → AuthService → RefreshTokenService → H2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RefreshTokenIntegrationTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
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

    private String login() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD));
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(res.getResponse().getContentAsString()).get("data");
        return data.get("refreshToken").asText();
    }

    @Test
    void login_devraitDelivrerUnRefreshToken() throws Exception {
        assertThat(login()).isNotBlank();
    }

    @Test
    void refresh_devraitDelivrerUnNouvelAccessTokenEtUnNouveauRefresh() throws Exception {
        String refresh = login();
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", refresh));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
    }

    @Test
    void refresh_reutilisationDeLAncienToken_devraitRetourner401() throws Exception {
        String refresh = login();
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", refresh));

        // 1er usage : OK (l'ancien token est supprimé par la rotation)
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // 2e usage du MÊME token : rejeté (rotation → token supprimé)
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_avecTokenInexistant_devraitRetourner401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", "jeton-inexistant"));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }
}
