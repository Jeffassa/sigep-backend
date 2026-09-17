package ci.esatic.sigep.integration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Une classe Tailwind qui ne produit aucune règle ne prévient personne.
 *
 * <p>Nos couleurs de thème (<code>ink</code>, <code>muted</code>, <code>line</code>…) sont
 * déclarées comme des variables CSS. Tailwind ne sait pas leur appliquer un modificateur
 * d'opacité — il lui faudrait les canaux séparés — et n'émet alors <b>rien du tout</b>, sans
 * avertissement au build.
 *
 * <p>Le résultat est invisible en thème clair et laid en thème sombre : dix-sept
 * <code>border-line/60</code> disséminés dans huit gabarits retombaient sur le gris très pâle
 * du <i>preflight</i>, si bien que les séparateurs de lignes des tableaux d'administration
 * disparaissaient sur fond noir. Personne ne l'avait vu, parce que rien ne casse.
 *
 * <p>Ce test regarde ce que le build a réellement produit, pas ce que les gabarits demandent.
 */
class ClassesTailwindVivantesTest {

    /** Les couleurs du thème, définies en variables CSS dans tailwind.config.js. */
    private static final List<String> COULEURS_DE_THEME =
            List.of("ink", "ink-2", "muted", "line", "paper");

    /** Utilitaires qui acceptent un modificateur d'opacité « /NN ». */
    private static final String UTILITAIRES = "border|text|bg|ring|divide|from|to|via";

    private static final Pattern OPACITE_SUR_THEME = Pattern.compile(
            "\\b((?:" + UTILITAIRES + ")-(?:" + String.join("|", COULEURS_DE_THEME) + "))/(\\d{1,3})\\b");

    @Test
    void aucunGabarit_nAppliqueUneOpaciteAUneCouleurDeTheme() throws IOException {
        List<String> fautives = new ArrayList<>();
        Path racine = Path.of("src/main/resources/templates");

        try (Stream<Path> fichiers = Files.walk(racine)) {
            for (Path f : fichiers.filter(p -> p.toString().endsWith(".html")).toList()) {
                String contenu = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = OPACITE_SUR_THEME.matcher(contenu);
                while (m.find()) {
                    fautives.add(racine.relativize(f) + " : " + m.group());
                }
            }
        }

        assertThat(fautives)
                .as("ces classes ne produisent aucune regle CSS ; ecrire la couleur sans "
                        + "modificateur, ou definir la couleur en canaux separes")
                .isEmpty();
    }

    @Test
    void laCouleurDeBordureDuTheme_existeBienDansLeBuild() throws IOException {
        // Le pendant du test précédent : la classe de remplacement, elle, doit exister.
        // Sans cette vérification, remplacer une classe morte par une autre passerait.
        assertThat(css()).contains(".border-line");
    }

    private String css() throws IOException {
        try (var flux = new ClassPathResource("static/css/tailwind.css").getInputStream()) {
            return new String(flux.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
