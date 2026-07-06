package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vérifie le budget GLOBAL par IP (filet anti-flood, toutes routes) : au-delà du budget,
 * même une route publique non sensible renvoie 429.
 * Rate-limiting réactivé localement (désactivé par défaut en profil test).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.login-rate-limit.enabled=true",
        "app.security.login-rate-limit.max-global=3"
})
class GlobalRateLimitIntegrationTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;

    @Test
    void requetesRepetees_depassantLeBudgetGlobal_sontBloquees() throws Exception {
        // max-global=3 : les 3 premières passent, la 4e dépasse le budget global (429).
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/error")); // route publique, non exclue du budget global
        }
        mockMvc.perform(get("/error")).andExpect(status().isTooManyRequests());
    }
}
