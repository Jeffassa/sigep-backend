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
 * <p>Nos utilisateurs se connectent souvent en 3G depuis un téléphone. L'accueil anime ses sections
 * avec GSAP et montre un QR en relief avec Three.js, mais à trois conditions : les bibliothèques
 * sont servies par SIGEP lui-même, elles ne bloquent jamais l'affichage, et Three.js n'est jamais
 * demandé par la page — c'est le module du QR qui le charge, et seulement sur grand écran.
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
    void lesBibliotheques_viennentDuSite_etNeBloquentPasLAffichage() throws Exception {
        String page = accueil();
        // Aucun site tiers : ni CDN, ni dépendance à un service extérieur pour s'afficher.
        assertThat(page)
                .doesNotContain("cdnjs.cloudflare.com")
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("unpkg.com");
        // GSAP est chargé en différé, depuis le site.
        assertThat(page)
                .contains("<script defer src=\"/js/vendor/gsap.min.js\"></script>")
                .contains("<script defer src=\"/js/vendor/ScrollTrigger.min.js\"></script>");
        // Three.js (le plus lourd) n'est jamais demandé par la page elle-même : sur téléphone,
        // il n'est pas téléchargé du tout.
        assertThat(page).doesNotContain("three.min.js");
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
