package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * Les quatre défauts introduits par la navigation instantanée.
 *
 * <p>Remplacer le contenu d'une page sans la recharger fait disparaître tout ce que le
 * rechargement faisait gratuitement : l'exécution des scripts de la page, la fermeture des
 * fenêtres superposées, la remise à zéro des écouteurs. Ces tests fixent ce qui doit rester vrai.
 *
 * <p>Les trois premiers lisent les gabarits eux-mêmes plutôt qu'une page rendue : ce sont des
 * propriétés du code de navigation, communes à toutes les pages, et qu'aucun rendu isolé ne
 * démontrerait.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NavigationInstantaneeTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;

    private String gabarit(String chemin) throws Exception {
        try (var flux = new ClassPathResource("templates/" + chemin).getInputStream()) {
            return new String(flux.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "admin/abonnement.html", "admin/dashboard.html", "admin/rapports.html",
            "admin/statistiques.html", "admin/momo-attente.html",
            "plateforme/dashboard.html", "plateforme/etablissement-nouveau.html"})
    void chaqueScriptDePage_estMarquePourEtreRejoue(String page) throws Exception {
        String html = gabarit(page);

        // Un contenu posé par innerHTML n'exécute aucun script. Sans cette marque, la page
        // arrive complète à l'œil et morte au clic — et si son script est écrit hors du <main>,
        // il n'est même pas recopié.
        int scripts = compter(html, "<script");
        int marques = compter(html, "data-page-script");
        assertThat(marques).as("scripts rejouables de " + page).isEqualTo(scripts);
    }

    @Test
    void leScriptCommun_nEstJamaisRejoue() throws Exception {
        // Le réexécuter réarmerait une deuxième fois la navigation, la palette et le dock :
        // chaque clic partirait alors en double.
        // On inspecte les BALISES ouvrantes, et non le fichier entier : le code de rejeu cite
        // forcément « data-page-script » dans ses sélecteurs, ce qui n'est pas une marque posée.
        String fragments = gabarit("admin/fragments.html");
        int marques = 0;
        for (int i = fragments.indexOf("<script"); i >= 0; i = fragments.indexOf("<script", i + 7)) {
            int fin = fragments.indexOf('>', i);
            if (fin > i && fragments.substring(i, fin).contains("data-page-script")) {
                marques++;
            }
        }
        assertThat(marques).as("aucun script du fragment commun ne doit se declarer rejouable")
                .isZero();
    }

    @Test
    void laPalette_seRefermeQuandOnSuitUneDeSesEntrees() throws Exception {
        String fragments = gabarit("admin/fragments.html");

        // Le rechargement la faisait disparaître ; sans rechargement, elle restait affichée
        // par-dessus la page qu'elle venait d'ouvrir.
        int posAller = fragments.indexOf("function aller(");
        int posFermeture = fragments.indexOf("__closePalette", posAller);
        assertThat(posAller).as("la fonction de navigation doit exister").isNotNegative();
        assertThat(posFermeture).as("la palette doit etre refermee au depart d'une navigation")
                .isBetween(posAller, posAller + 600);
    }

    @Test
    void leDock_nEstPasRearmeAChaqueNavigation() throws Exception {
        String fragments = gabarit("admin/fragments.html");

        // Ses écouteurs sont posés sur l'élément lui-même, qui survit au remplacement, et il
        // relit ses éléments à chaque mouvement. Le réarmer empilait deux gestionnaires de plus
        // par navigation, tous exécutant le même calcul à chaque déplacement de souris.
        assertThat(fragments).as("le rearmement du dock apres remplacement fuyait des ecouteurs")
                .doesNotContain("dock.dataset.ready = ''");
    }

    @Test
    void laDeconnexion_resteUneNavigationClassique() throws Exception {
        String fragments = gabarit("admin/fragments.html");

        // Elle aboutit hors du périmètre de l'administration : interceptée, elle poserait la
        // page de connexion à l'intérieur du cadre admin, dock et en-tête toujours présents.
        int posFormulaire = fragments.indexOf("id=\"logoutForm\"");
        assertThat(posFormulaire).isNotNegative();
        assertThat(fragments.substring(posFormulaire, fragments.indexOf('>', posFormulaire)))
                .contains("data-no-boost");
    }

    @Test
    void laCaseDesArchives_declencheBienUneSoumission() throws Exception {
        String liste = gabarit("admin/enseignants.html");

        // form.submit() appelé depuis un script ne déclenche PAS l'évènement « submit » :
        // la navigation instantanée ne pouvait donc pas l'intercepter, et la page se rechargeait.
        assertThat(liste).contains("requestSubmit");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unChangementDeStatut_rendLaListeTelleQuelleEtait() throws Exception {
        // Sans cela, on revenait au début d'une liste complète — sans le filtre qui avait permis
        // de trouver l'enseignant, et sans l'enseignant, qui venait d'en sortir.
        mockMvc.perform(post("/admin/enseignants/1/statut").with(csrf())
                        .param("statut", "ARCHIVE")
                        .param("search", "Koné")
                        .param("departement", "Informatique")
                        .param("archives", "true")
                        .param("page", "2"))
                .andExpect(redirectedUrl(
                        "/admin/enseignants?search=Kon%C3%A9&departement=Informatique&archives=true&page=2"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unChangementDeStatut_sansFiltre_renvoieVersLaListeNue() throws Exception {
        // Le cas courant ne doit pas hériter d'une adresse encombrée de paramètres vides.
        mockMvc.perform(post("/admin/enseignants/1/statut").with(csrf())
                        .param("statut", "VALIDATED"))
                .andExpect(redirectedUrl("/admin/enseignants"));
    }

    @Test
    void lesScripts_sontPosesUnParUn_pourQueLaBibliothequeArriveAvantSonCode() throws Exception {
        String fragments = gabarit("admin/fragments.html");

        // « async = false » n'ordonne que les scripts EXTERNES entre eux : un script EN LIGNE
        // inséré par script s'exécute immédiatement. Posés d'un bloc, le code du tableau de bord
        // partait avant ECharts, abandonnait sur « if (!window.echarts) return », et laissait
        // trois cadres vides — puis fonctionnait au passage suivant, donc invisible en recette.
        assertThat(fragments).as("l'enchainement doit attendre le chargement de chaque bibliotheque")
                .contains("neuf.onload = neuf.onerror = suivant");
        assertThat(fragments).as("async=false ne suffisait pas et donnait une fausse assurance")
                .doesNotContain("neuf.async = false");
    }

    @Test
    void laTraceDesBibliotheques_survitAuRetraitDesBalises() throws Exception {
        String fragments = gabarit("admin/fragments.html");

        // Relevée dans le document APRÈS le retrait des balises, elle serait toujours vide :
        // ECharts serait alors réévalué à chaque entrée, écrasant window.echarts sous les
        // graphiques qu'on vient d'y construire.
        assertThat(fragments).contains("window.__bibliothequesDePage");
    }

    @Test
    void chaqueScriptQuiLaisseQuelqueChoseEnRoute_saitSeDemonter() throws Exception {
        // Retirer la balise d'un script n'annule ni minuterie, ni écouteur, ni observateur.
        assertThat(gabarit("admin/fragments.html")).contains("window.__surDemontage");

        for (String page : new String[] {
                "admin/dashboard.html", "plateforme/dashboard.html", "admin/momo-attente.html"}) {
            assertThat(gabarit(page)).as("demontage de " + page).contains("__surDemontage");
        }
    }

    @Test
    void laPagination_neConstruitAucuneSequenceQuandLaListeEstVide() throws Exception {
        String liste = gabarit("admin/enseignants.html");

        // th:each s'évalue AVANT th:if sur une même balise : il faut donc imbriquer. Sans cela,
        // une liste vide donne totalPages=0 et sequence(0, -1) rend [0, -1] — d'où une pastille
        // menant à une page négative, que le serveur refuse. C'est ce qui arrivait juste après
        // avoir archivé le dernier résultat d'une recherche.
        int garde = liste.indexOf("th:if=\"${totalPages > 0}\"");
        int sequence = liste.indexOf("#numbers.sequence");
        assertThat(garde).as("la garde sur totalPages doit exister").isNotNegative();
        assertThat(garde).as("la garde doit ENVELOPPER la sequence, pas la partager").isLessThan(sequence);
        assertThat(liste.substring(garde, sequence)).as("les deux attributs ne doivent pas etre sur la meme balise")
                .contains(">");
    }

    @Test
    void lesTroisActionsDeLigne_transmettentLeContexteDeLaListe() throws Exception {
        // Statut, renvoi des accès et suppression partent toutes de la même liste filtrée.
        // Le refus de suppression conseille d'archiver : encore faut-il retrouver l'enseignant.
        // On compte les champs CACHÉS : la barre de filtres porte elle aussi un champ « search »,
        // mais visible, et il ne relève pas de ce report de contexte.
        String liste = gabarit("admin/enseignants.html");
        assertThat(compter(liste, "type=\"hidden\" name=\"search\"")).isEqualTo(3);
        assertThat(compter(liste, "type=\"hidden\" name=\"page\"")).isEqualTo(3);
        assertThat(compter(liste, "type=\"hidden\" name=\"archives\"")).isEqualTo(3);
    }

    @Test
    void leMessageDErreur_resisteALAutoActualisation() throws Exception {
        // Sans data-flash, le rafraîchissement automatique effaçait en moins d'une seconde le
        // message qui explique pourquoi la suppression a échoué et quoi faire à la place.
        for (String page : new String[] {
                "admin/enseignants.html", "admin/rapports.html", "admin/referentiels.html"}) {
            String html = gabarit(page);
            int erreur = html.indexOf("th:if=\"${error}\"");
            assertThat(erreur).as("bloc d'erreur de " + page).isNotNegative();
            assertThat(html.substring(Math.max(0, erreur - 60), erreur))
                    .as("le bloc d'erreur de " + page + " doit etre protege").contains("data-flash");
        }
    }

    private static int compter(String texte, String motif) {
        int n = 0;
        for (int i = texte.indexOf(motif); i >= 0; i = texte.indexOf(motif, i + motif.length())) {
            n++;
        }
        return n;
    }
}
