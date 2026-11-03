package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Le port d'écoute en production est imposé par l'hébergeur. Une requête en clair qui y arrive,
 * sonde de santé comprise, doit être redirigée : le filtre de Spring Security 7 répondait 500
 * pour tout port hors de sa table, et aucun test sur le port par défaut ne pouvait le voir.
 */
@SpringBootTest(properties = "app.security.require-https=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RedirectionHttpsTest {

    @MockitoBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;

    @Test
    void uneRequeteEnClairSurUnPortQuelconque_estRedirigee() throws Exception {
        var reponse = mockMvc.perform(get("/actuator/health").with(requete -> {
            requete.setServerPort(10000);
            return requete;
        })).andReturn().getResponse();

        assertThat(reponse.getStatus()).isBetween(300, 399);
        assertThat(reponse.getRedirectedUrl()).startsWith("https://").doesNotContain("10000");
    }

    @Test
    void uneRequeteDejaSecurisee_passe() throws Exception {
        var reponse = mockMvc.perform(get("/robots.txt").with(requete -> {
            // Ce que produit X-Forwarded-Proto derrière le proxy : le filtre lit le schéma.
            requete.setScheme("https");
            requete.setServerPort(443);
            return requete;
        })).andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(200);
    }
}
