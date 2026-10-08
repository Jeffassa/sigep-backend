package ci.esatic.sigep.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Une règle de style enfermée dans {@code :root} ne prévient personne.
 *
 * <h2>Ce qui est arrivé</h2>
 *
 * <p>Un bloc entier — la coche de réussite, ses deux animations, sa variante pour qui demande
 * moins de mouvement — a été inséré <b>à l'intérieur</b> de {@code :root { }}. La feuille se
 * chargeait, les pages s'affichaient, aucun outil ne protestait. Et pourtant deux choses se
 * perdaient en silence :
 *
 * <ul>
 *   <li>{@code @keyframes} n'est pas admis dans une règle de style : les deux animations étaient
 *       <b>purement et simplement supprimées</b> par l'analyseur du navigateur. La coche restait
 *       à son décalage de tracé initial — c'est-à-dire <b>invisible</b> — sur les dix messages de
 *       confirmation de l'administration. Elle l'est restée une version entière.</li>
 *   <li>{@code html.dark .alerte} devenait {@code :root html.dark .alerte}, qui ne correspond à
 *       rien : {@code html.dark} ne peut pas être un descendant de {@code :root}, c'est le même
 *       élément. La variante sombre ne s'appliquait donc jamais.</li>
 * </ul>
 *
 * <p>Ces deux défauts ont la même cause et la même signature : <b>rien ne casse</b>. Le
 * navigateur applique l'imbrication, la page se rend, et seul l'œil — s'il sait quoi chercher —
 * remarque qu'une icône manque. C'est exactement le genre de chose qu'un test doit tenir, parce
 * qu'une relecture ne la voit pas.
 *
 * <h2>Ce que ce test exige</h2>
 *
 * <p>Les blocs qui ne déclarent que des variables de thème — {@code :root}, {@code html.dark} —
 * ne contiennent que des déclarations. Aucune accolade ouvrante, donc aucune règle imbriquée,
 * aucune animation, aucune requête de média.
 */
class ReglesCssHorsRacineTest {

    private static final List<String> FEUILLES = List.of(
            "src/main/resources/static/css/sigep.css");

    /** Blocs dont le rôle est de déclarer des variables, et rien d'autre. */
    private static final List<String> BLOCS_DE_VARIABLES = List.of(":root {", "html.dark {");

    @Test
    void aucuneRegle_neSeCacheDansUnBlocDeVariables() throws IOException {
        List<String> fautifs = new ArrayList<>();

        for (String feuille : FEUILLES) {
            String css = Files.readString(Path.of(feuille), StandardCharsets.UTF_8);
            for (String ouverture : BLOCS_DE_VARIABLES) {
                int depart = 0;
                while (true) {
                    int i = css.indexOf(ouverture, depart);
                    if (i < 0) break;
                    int contenuDebut = i + ouverture.length();
                    int fermeture = css.indexOf('}', contenuDebut);
                    int imbrication = css.indexOf('{', contenuDebut);

                    // Une accolade ouvrante AVANT la fermeture du bloc : quelque chose a été
                    // glissé à l'intérieur. C'est précisément le défaut qu'on traque.
                    if (imbrication >= 0 && (fermeture < 0 || imbrication < fermeture)) {
                        int ligne = (int) css.substring(0, imbrication).chars()
                                .filter(c -> c == '\n').count() + 1;
                        String extrait = css.substring(Math.max(contenuDebut, imbrication - 80),
                                Math.min(css.length(), imbrication + 1)).strip();
                        fautifs.add(feuille + " : règle imbriquée dans « " + ouverture.strip()
                                + " » vers la ligne " + ligne + " — …" + extrait);
                    }
                    depart = contenuDebut;
                }
            }
        }

        assertThat(fautifs)
                .as("un bloc de variables ne doit contenir que des déclarations : une règle qui s'y "
                        + "trouve est appliquée autrement qu'écrit, et une @keyframes y est "
                        + "silencieusement supprimée")
                .isEmpty();
    }

    @Test
    void lesAnimationsCitees_existentVraiment() throws IOException {
        // Le pendant du test précédent, pris par l'autre bout : peu importe POURQUOI une
        // animation manque — bloc mal placé, nom mal orthographié, règle supprimée au nettoyage —
        // une « animation: coche-cercle » qui ne mène à aucune « @keyframes coche-cercle » ne
        // produit rien, et rien ne le signale.
        List<String> manquantes = new ArrayList<>();

        for (String feuille : FEUILLES) {
            String css = Files.readString(Path.of(feuille), StandardCharsets.UTF_8);
            var declarees = new java.util.HashSet<String>();
            var m = java.util.regex.Pattern.compile("@keyframes\\s+([A-Za-z_][\\w-]*)").matcher(css);
            while (m.find()) declarees.add(m.group(1));

            var utilisees = java.util.regex.Pattern
                    .compile("animation(?:-name)?\\s*:\\s*([^;}]+)").matcher(css);
            while (utilisees.find()) {
                for (String morceau : utilisees.group(1).split("[,\\s]+")) {
                    String nom = morceau.strip();
                    // On ne garde que ce qui ressemble à un nom d'animation : ni durée, ni
                    // fonction de transition, ni mot-clé.
                    if (!nom.matches("[A-Za-z_][\\w-]*")) continue;
                    if (MOTS_CLES.contains(nom)) continue;
                    if (!declarees.contains(nom)) manquantes.add(feuille + " : " + nom);
                }
            }
        }

        assertThat(manquantes)
                .as("ces animations sont demandées mais ne sont déclarées nulle part : elles ne "
                        + "jouent pas, et l'élément reste figé dans son état initial")
                .isEmpty();
    }

    /** Mots qui apparaissent dans un raccourci {@code animation} sans être un nom d'animation. */
    private static final java.util.Set<String> MOTS_CLES = java.util.Set.of(
            "none", "infinite", "alternate", "alternate-reverse", "reverse", "normal",
            "forwards", "backwards", "both", "running", "paused", "linear", "ease",
            "ease-in", "ease-out", "ease-in-out", "step-start", "step-end", "initial",
            "inherit", "unset", "revert", "s", "ms");
}
