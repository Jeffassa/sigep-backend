package ci.esatic.sigep.integration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les finitions qui séparent une interface correcte d'une interface soignée.
 *
 * <p>Aucune ne se voit isolément, et c'est justement pourquoi elles disparaissent à la première
 * refonte de feuille de style si rien ne les retient. Chacune répond à une gêne précise, notée
 * ici pour qu'on sache ce qu'on casserait en la retirant.
 */
class FinitionInterfaceTest {

    private String socle() throws Exception {
        try (var flux = new ClassPathResource("static/css/sigep.css").getInputStream()) {
            return new String(flux.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void lesChiffresSAlignentEnColonne() throws Exception {
        // Des heures et des taux empilés sans chasse fixe font onduler les colonnes : l'oeil ne
        // peut plus comparer deux lignes d'un coup. C'est le défaut le plus visible d'un tableau
        // de gestion, et le moins cher à corriger.
        assertThat(socle()).contains("tabular-nums");
    }

    @Test
    void leClavierSeVoit() throws Exception {
        String css = socle();
        // focus-visible, et non focus : la marque est réservée à qui navigue au clavier, elle
        // n'apparaît pas au clic de souris.
        assertThat(css).contains(":focus-visible");
        assertThat(css).contains("outline-offset");
    }

    @Test
    void unBoutonDesactiveLeDit() throws Exception {
        // Sans signe, l'utilisateur clique, rien ne se passe, et il croit l'outil cassé.
        assertThat(socle()).contains("button:disabled").contains("not-allowed");
    }

    @Test
    void unBoutonQuiTravailleLeMontre() throws Exception {
        String css = socle();
        // Un bouton muet pendant un envoi invite au double clic — et un second clic sur
        // « supprimer » ne se rattrape pas.
        assertThat(css).contains(".en-cours");
        assertThat(css).contains("pointer-events: none");
    }

    @Test
    void laMiseEnMouvement_respecteCeuxQuiNEnVeulentPas() throws Exception {
        String css = socle();
        int garde = css.indexOf("prefers-reduced-motion");
        assertThat(garde).as("la preference doit etre honoree").isNotNegative();

        // Et elle doit couvrir les transitions ajoutées, pas seulement les anciennes.
        assertThat(css.substring(garde)).contains("tbody tr");
    }

    @Test
    void lesFinitions_nAlourdissentPasLaPage() throws Exception {
        // Ce socle est chargé sur chaque page d'administration. Il porte la finition de toutes
        // les pages à la fois : c'est ce qui permet de ne charger aucune bibliothèque ici.
        assertThat(socle().length())
                .as("le socle doit rester sous 32 Ko : au-dela, il faudrait le decouper")
                .isLessThan(32_000);
    }
}
