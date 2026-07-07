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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vérifie que le login web admin (/admin-login) est protégé par le rate-limiting :
 * budget dédié anti-bruteforce du back-office (nouvelle couverture).
 * Rate-limiting réactivé localement (désactivé par défaut en profil test).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.login-rate-limit.enabled=true",
        "app.security.login-rate-limit.max-admin-login=2"
})
class AdminLoginRateLimitIntegrationTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;

    @Test
    void adminLogin_devraitEtreRateLimiteAuDela_duBudget() throws Exception {
        // max-admin-login=2 : les 2 premières tentatives atteignent le form-login
        // (identifiants invalides), la 3e est bloquée en amont par le filtre (429).
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/admin-login").with(csrf())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("username", "intrus@example.com").param("password", "faux"));
        }
        mockMvc.perform(post("/admin-login").with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "intrus@example.com").param("password", "faux"))
                .andExpect(status().isTooManyRequests());
    }
}
