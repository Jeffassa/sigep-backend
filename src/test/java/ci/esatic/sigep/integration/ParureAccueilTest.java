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
    void uneDecoration_nePeutJamaisEffacerUnBoutonDAppel() throws Exception {
        // Le défaut que ce test retient : l'entrée du hero visait « .hero-cta > * », qui
        // désigne TOUS les appels à l'action de la page. gsap.from() pose opacity:0 à
        // l'instant même et ne le relève qu'en fin de tween — si le tween ne finit jamais
        // (onglet ouvert en arrière-plan, donc requestAnimationFrame gelé ; appareil lent ;
        // script interrompu), le visiteur arrive sur une page dont les cinq boutons sont
        // invisibles. C'est toute la conversion du site qui disparaît, sans erreur nulle part.
        String html = accueil();

        assertThat(html)
                .as("l'entree du hero ne doit viser que le hero")
                .doesNotContain(".from('.hero-cta > *'");
        assertThat(html).contains(".hero .hero-cta > *");

        // Et quoi qu'il arrive, les styles posés par l'animation sont effacés.
        assertThat(html)
                .as("un filet doit rendre le contenu visible meme si l'animation n'aboutit pas")
                .contains("clearProps")
                .contains("setTimeout(rendreVisible");
    }

    @Test
    void leFondAnime_neDependDAucuneBibliotheque() throws Exception {
        String html = accueil();

        // Le fond était rendu par Three.js — 130 Ko compressés pour une grille de points, et
        // rien du tout pour qui n'obtient pas de contexte WebGL. Il tient désormais dans un
        // canvas 2D : aucune dépendance, et le repli est la page elle-même.
        assertThat(html).doesNotContain("three.min.js").doesNotContain("THREE.");
        assertThat(html).contains("function fond()").contains("getContext('2d')");

        // Le canevas est posé DERRIÈRE la page : l'effacement de la traînée doit retirer de
        // l'alpha, jamais repeindre un fond opaque qui masquerait le contenu.
        assertThat(html).contains("destination-out");
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
