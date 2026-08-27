package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Les pages publiques (marketing, légales, SEO) doivent être accessibles SANS authentification
 * et se rendre sans erreur Thymeleaf. Verrouille le SEO/conformité + la config de sécurité.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicPagesIntegrationTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;

    @Test
    void pagesPubliques_accessiblesEtRendues() throws Exception {
        for (String url : new String[]{"/", "/inscription", "/mentions-legales", "/confidentialite", "/cgu",
                "/robots.txt", "/sitemap.xml"}) {
            mockMvc.perform(get(url)).andExpect(status().isOk());
        }
    }
}
