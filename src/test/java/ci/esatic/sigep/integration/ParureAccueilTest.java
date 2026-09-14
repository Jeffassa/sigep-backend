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
 * La parure animée de l'accueil ne doit jamais se payer sur le dos du visiteur.
 *
 * <p>Les bibliothèques d'animation pèsent plusieurs centaines de kilo-octets. Nos utilisateurs
 * se connectent souvent en 3G depuis un téléphone : les imposer reviendrait à faire payer la
 * décoration par ceux qui ont le moins de débit. Ces tests fixent les garde-fous, parce qu'ils
 * sont invisibles — une page qui charge trop reste belle, elle est simplement lente pour
 * quelqu'un qu'on ne verra jamais se plaindre.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ParureAccueilTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;

    private String accueil() throws Exception {
        return mockMvc.perform(get("/")).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aucuneBibliothequeDAnimation_nEstChargeeParLeDocument() throws Exception {
        String html = accueil();

        // Aucune balise <script src> vers ces bibliothèques : elles ne sont demandées qu'à
        // l'exécution, après examen des conditions. Une balise dans le document les imposerait
        // à tout le monde, y compris à qui coche « économiseur de données ».
        assertThat(html)
                .as("les bibliotheques doivent etre demandees par le script, jamais declarees")
                .doesNotContain("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/three.js")
                .doesNotContain("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/gsap");
    }

    @Test
    void lesConditionsDeChargement_sontToutesPresentes() throws Exception {
        String html = accueil();

        // Chacune répond à un visiteur réel : celui qui économise ses données, celui qui a une
        // connexion lente, celui qui est sur un téléphone, celui qui a demandé moins d'animations.
        assertThat(html).contains("saveData");
        assertThat(html).contains("effectiveType");
        assertThat(html).contains("prefers-reduced-motion");
        assertThat(html).contains("innerWidth < 900");
    }

    @Test
    void laParure_attendQueLaPageSoitAffichee() throws Exception {
        // Chargée sur l'évènement « load » : elle ne doit pas disputer la bande passante au
        // texte, qui est la seule chose dont le visiteur ait besoin.
        assertThat(accueil()).contains("addEventListener('load'");
    }

    @Test
    void laTrame_estDecorativeEtNonCliquable() throws Exception {
        String html = accueil();
        int canvas = html.indexOf("id=\"trame\"");
        assertThat(canvas).as("le canevas doit exister").isNotNegative();

        // Masquée aux lecteurs d'écran : elle ne porte aucune information.
        assertThat(html.substring(Math.max(0, canvas - 120), canvas + 60)).contains("aria-hidden");
        // Et jamais au-dessus des liens : un fond décoratif qui intercepte les clics est un piège.
        assertThat(html).contains("pointer-events: none");
    }

    @Test
    void laPage_resteEntiereSansAucunScript() throws Exception {
        String html = accueil();

        // Le texte, les prix et les liens ne dépendent de rien : le repli existait déjà pour
        // la révélation au défilement, il doit survivre à l'ajout de la parure.
        assertThat(html).contains("<noscript>");
        assertThat(html).contains("Démarrer gratuitement");
        assertThat(html).contains("FCFA");
    }
}
