package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * La carte « Émargement QR » de l'accueil montre bien un code.
 *
 * <p>Elle n'affichait qu'un carré blanc : le motif venait d'un fond CSS où
 * {@code conic-gradient(var(--text) 0 0)} ne produisait pas un damier mais un aplat de la
 * couleur du texte, tandis que la grille superposée se dessinait dans cette même couleur.
 * Du blanc sur du blanc, sur la carte qui illustre la fonction principale du produit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CarteQrAccueilTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;

    @Test
    void laCarteDAccueil_dessineUnCodeEtNonUnCarreVide() throws Exception {
        String html = mockMvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        int carte = html.indexOf("Émargement QR");
        assertThat(carte).as("la carte doit exister").isNotNegative();

        String bloc = html.substring(carte, Math.min(carte + 4000, html.length()));
        assertThat(bloc).as("le motif doit etre dessine, pas laisse a un fond CSS")
                .contains("qr-box").contains("<path");
        assertThat(bloc).doesNotContain("<div class=\"qr-box\"></div>");

        // Le fond qui ne dessinait rien ne doit pas revenir.
        assertThat(html).as("l'aplat de couleur de texte etait la cause du carre vide")
                .doesNotContain("conic-gradient(var(--text) 0 0)");
    }
}
