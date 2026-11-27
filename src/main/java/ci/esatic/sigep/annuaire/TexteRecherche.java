package ci.esatic.sigep.annuaire;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Ramène un nom à sa forme de recherche : minuscules, sans accents ni ponctuation. */
public final class TexteRecherche {

    private TexteRecherche() {
    }

    public static String normaliser(String texte) {
        if (texte == null) return "";
        String sansAccents = Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return sansAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /**
     * La clé de rapprochement d'un nom : sa forme de recherche, sans le sigle entre parenthèses.
     * « ABIDJAN BUSINESS SCHOOL -(ABS) » et « Abidjan Business School » ont la même clé.
     */
    public static String cle(String nom) {
        if (nom == null) return "";
        return normaliser(nom.replaceAll("\\([^)]*\\)", " "));
    }

    /** Les mots d'une recherche, du plus long au plus court (le plus long filtre le mieux). */
    public static List<String> mots(String recherche) {
        String n = normaliser(recherche);
        if (n.isEmpty()) return List.of();
        return Arrays.stream(n.split(" ")).filter(m -> !m.isEmpty()).distinct()
                .sorted((a, b) -> Integer.compare(b.length(), a.length())).toList();
    }
}
