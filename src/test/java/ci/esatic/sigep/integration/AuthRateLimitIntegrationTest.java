package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vérifie que /api/auth/refresh est protégé par le rate-limiting (budget propre, séparé du login).
 * Le rate-limiting est réactivé localement (désactivé par défaut en profil test).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.login-rate-limit.enabled=true",
        "app.security.login-rate-limit.max-refresh=2"
})
class AuthRateLimitIntegrationTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;

    @Test
    void refresh_devraitEtreRateLimiteAuDela_duBudget() throws Exception {
        String body = "{\"refreshToken\":\"peu-importe\"}";

        // max-refresh=2 : les 2 premières passent au contrôleur (token invalide → 401),
        // la 3e est bloquée en amont par le filtre (429).
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body));
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body));
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
    }
}
