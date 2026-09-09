package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Ce que voit un moteur de recherche sur les pages publiques.
 *
 * <p>Ces pages sont rendues par Thymeleaf : une expression fautive ne se manifeste qu'au rendu,
 * jamais à la compilation. Elles partageaient de surcroît leur en-tête avec l'administration, si
 * bien qu'une balise posée pour l'une retombait sur l'autre — l'en-tête commun annonçait
 * « index,follow » sur une vingtaine de pages privées. Ces tests fixent les deux intentions :
 * ce qui doit être trouvé, et ce qui ne doit pas l'être.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferencementPagesPubliquesTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;

    private static int compter(String texte, String motif) {
        int n = 0;
        for (int i = texte.indexOf(motif); i >= 0; i = texte.indexOf(motif, i + motif.length())) {
            n++;
        }
        return n;
    }

    private String rendu(String url) throws Exception {
        var reponse = mockMvc.perform(get(url)).andReturn().getResponse();
        assertThat(reponse.getStatus()).as("statut de " + url).isEqualTo(200);
        return reponse.getContentAsString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/inscription", "/mentions-legales", "/confidentialite", "/cgu"})
    void chaquePagePublique_porteSesReperesDeReferencement(String url) throws Exception {
        String html = rendu(url);

        assertThat(html).as("titre de " + url).contains("<title>");
        assertThat(html).as("description de " + url).contains("name=\"description\"");
        assertThat(html).as("indexation de " + url).contains("index,follow");
        assertThat(html).as("carte de partage de " + url).contains("property=\"og:title\"");
        assertThat(html).as("image de partage de " + url).contains("og-sigep.png");

        // La canonique doit être ABSOLUE et désigner le site public, jamais l'hôte reçu :
        // l'application répond aussi sur son adresse d'hébergement, et recopier cet hôte
        // désignerait une seconde version du site.
        assertThat(html).as("canonique de " + url).contains("rel=\"canonical\"");
        assertThat(html).as("canonique absolue de " + url).contains("https://sigep.store");
    }

    @Test
    void laCanonique_ignoreLesParametresDeCampagne() throws Exception {
        String html = rendu("/?utm_source=whatsapp&utm_campaign=rentree");

        // Sans cela, chaque lien partagé fabriquerait sa propre page de référence.
        assertThat(html).doesNotContain("utm_source");
        assertThat(html).contains("href=\"https://sigep.store/\"");
    }

    @Test
    void lesPagesLegales_neTelechargentPasLaPoliceDIcones() throws Exception {
        // 372 Ko pour des pages qui n'affichent pas une seule icône.
        for (String url : new String[] {"/cgu", "/confidentialite", "/mentions-legales"}) {
            assertThat(rendu(url)).as("police d'icones sur " + url)
                    .doesNotContain("Material+Symbols");
        }
    }

    @Test
    void lesDonneesStructurees_neSontPoseesQueSurLAccueil() throws Exception {
        assertThat(rendu("/")).contains("application/ld+json").contains("SoftwareApplication");
        assertThat(rendu("/cgu")).doesNotContain("application/ld+json");
    }

    @Test
    void lesDonneesStructurees_formentUnJsonValide() throws Exception {
        // Un balisage mal formé n'est pas signalé au visiteur : il est simplement ignoré, et le
        // travail est perdu sans que rien ne le dise. Une valeur non substituée par le moteur de
        // template — une apostrophe, un guillemet, une variable nulle — suffit à le casser.
        String html = rendu("/");
        int debut = html.indexOf("application/ld+json");
        int ouvre = html.indexOf('>', debut) + 1;
        int ferme = html.indexOf("</script>", ouvre);
        String json = html.substring(ouvre, ferme).trim();

        var noeud = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);

        assertThat(noeud.path("@graph")).as("le graphe doit porter ses trois noeuds").hasSize(3);
        assertThat(json).as("aucune expression de template ne doit subsister").doesNotContain("${");
        assertThat(noeud.path("@graph").get(0).path("url").asText())
                .as("les URL du balisage doivent etre absolues")
                .startsWith("https://");
    }

    @Test
    void lAccueil_resteLisibleSansJavaScript() throws Exception {
        String html = rendu("/");

        // Le contenu ne doit pas dépendre d'un script pour EXISTER : posé à opacity:0 dans la
        // feuille de style, tout ce qui suit l'accroche disparaissait dès que le script ne
        // s'exécutait pas, et un moteur n'avait qu'une page vide à indexer.
        // Toute règle qui masque doit être portée par la classe d'armement : on compte les deux
        // formes, et elles doivent coïncider. Chercher simplement l'absence de « .reveal
        // { opacity: 0 » ne prouverait rien — la forme conditionnée contient cette sous-chaîne.
        int masquages = compter(html, ".reveal { opacity: 0");
        int masquagesArmes = compter(html, "html.anim-reveal .reveal { opacity: 0");
        assertThat(masquages).as("au moins une regle de masquage doit exister").isPositive();
        assertThat(masquagesArmes)
                .as("tout masquage doit etre conditionne a l'armement du script")
                .isEqualTo(masquages);
        assertThat(html).as("filet pour les visiteurs sans JavaScript").contains("<noscript>");
    }

    @Test
    void lesRessourcesPubliques_redeviennentCachables() throws Exception {
        String css = mockMvc.perform(get("/css/sigep.css"))
                .andReturn().getResponse().getHeader("Cache-Control");
        assertThat(css).as("les feuilles de style etaient retelechargees a chaque page")
                .isEqualTo("public, max-age=3600");

        String accueil = mockMvc.perform(get("/"))
                .andReturn().getResponse().getHeader("Cache-Control");
        // « no-store » privait le navigateur de son cache de retour arrière ; la revalidation
        // conserve en revanche la fraîcheur du contenu.
        assertThat(accueil).doesNotContain("no-store").contains("must-revalidate");
    }

    @Test
    void lesReponsesPrivees_restentInterditesDeCache() throws Exception {
        // Non-négociable : c'est le défaut, l'assouplissement n'étant qu'une exception nommée.
        for (String url : new String[] {"/admin/dashboard", "/api/seances/a-emarger", "/admin-login"}) {
            String entete = mockMvc.perform(get(url)).andReturn().getResponse().getHeader("Cache-Control");
            assertThat(entete).as("cache de " + url).contains("no-store");
        }
    }

    @Test
    void lesPagesPrivees_seDeclarentNonIndexables() throws Exception {
        // /admin-login reste explorable exprès : liée depuis l'accueil, elle doit être visitée
        // pour que son « noindex » soit lu et qu'elle quitte l'index.
        assertThat(rendu("/admin-login"))
                .contains("noindex,nofollow")
                .doesNotContain("index,follow,max-image-preview");
    }
}
