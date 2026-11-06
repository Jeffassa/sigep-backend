package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Le mouvement de l'accueil ne doit jamais se payer sur le dos du visiteur.
 *
 * <p>Nos utilisateurs se connectent souvent en 3G depuis un téléphone. L'accueil se contente donc
 * de texte, de SVG et d'un script écrit à la main : ni bibliothèque d'animation, ni image décorative.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ParureAccueilTest {

    @MockitoBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;

    private String accueil() throws Exception {
        return mockMvc.perform(get("/")).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aucuneBibliotheque_nEstChargeeParLaPage() throws Exception {
        assertThat(accueil())
                .doesNotContain("cdnjs.cloudflare.com")
                .doesNotContain("gsap")
                .doesNotContain("three.min.js");
    }

    @Test
    void leMouvement_respecteCeuxQuiNEnVeulentPas() throws Exception {
        assertThat(accueil()).contains("prefers-reduced-motion");
    }

    @Test
    void laPage_resteEntiereSansAucunScript() throws Exception {
        // L'offre, les tarifs et l'appel à l'action sont du HTML : un script qui ne se charge pas
        // retire la démonstration, jamais ce que le visiteur est venu lire.
        assertThat(accueil())
                .contains("<noscript>")
                .contains("Démarrer gratuitement")
                .contains("FCFA")
                .contains("Algorithmique");
    }
}
