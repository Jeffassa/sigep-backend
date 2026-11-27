package ci.esatic.sigep.service.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pseudonymes d'une conversation avec l'assistant : les noms, prénoms, matricules et adresses
 * e-mail partent chez le fournisseur d'IA sous forme de codes (« Nom_3 », « MAT_7 »…), et sont
 * rétablis à l'arrivée, avant d'être montrés à l'administrateur ou passés à un outil.
 *
 * <p>Un même nom garde le même code pendant toute la conversation : le modèle peut suivre
 * « Nom_3 » d'une question à l'autre sans jamais connaître la personne.
 *
 * <p>Limite assumée : un nom que l'administrateur tape lui-même et que SIGEP ne connaît pas
 * encore (un enseignant à créer) part tel quel ; la politique de confidentialité le dit.
 */
public class Pseudonymiseur {

    /** Champs des réponses de SIGEP qui portent une donnée personnelle. */
    private static final Map<String, String> CHAMPS = Map.ofEntries(
            Map.entry("nom", "Nom"),
            Map.entry("prenom", "Prenom"),
            Map.entry("enseignant", "Personne"),
            Map.entry("administrateur", "Personne"),
            Map.entry("matricule", "MAT"),
            Map.entry("email", "Courriel"),
            Map.entry("telephone", "Tel"));

    /** Un code tel que le modèle le recopie, éventuellement en adresse e-mail. */
    private static final Pattern CODE = Pattern.compile(
            "\\b(Nom|Prenom|Personne|MAT|Courriel|Tel)_(\\d+)(?:@masque\\.invalid)?\\b");

    /** En dessous, une valeur courte (« Li », « 12 ») remplacerait des mots ordinaires. */
    private static final int LONGUEUR_MIN_TEXTE = 3;

    private final Map<String, String> versCode = new HashMap<>();   // genre + valeur exacte -> code
    private final Map<String, String> versValeur = new HashMap<>(); // code -> valeur
    private final Map<String, String> pourTexte = new LinkedHashMap<>(); // valeur (minuscules) -> code
    private int compteur;

    /** Code de cette valeur, créé au premier passage. */
    public String code(String genre, String valeur) {
        String cle = genre + "\u0000" + valeur;
        String existant = versCode.get(cle);
        if (existant != null) return existant;
        String code = genre + "_" + (++compteur) + ("Courriel".equals(genre) ? "@masque.invalid" : "");
        versCode.put(cle, code);
        versValeur.put(code.replace("@masque.invalid", ""), valeur);
        if (valeur.trim().length() >= LONGUEUR_MIN_TEXTE) {
            pourTexte.putIfAbsent(valeur.trim().toLowerCase(Locale.ROOT), code);
        }
        return code;
    }

    /** Fait connaître une personne d'avance : son nom tapé par l'administrateur sera masqué aussi. */
    public void connaitre(String prenom, String nom, String matricule, String email) {
        if (prenom != null && !prenom.isBlank()) code("Prenom", prenom.trim());
        if (nom != null && !nom.isBlank()) code("Nom", nom.trim());
        if (matricule != null && !matricule.isBlank()) code("MAT", matricule.trim());
        if (email != null && !email.isBlank()) code("Courriel", email.trim());
    }

    /**
     * Copie d'une réponse de SIGEP (cartes, listes, textes) où chaque donnée personnelle est
     * remplacée par son code. Les champs connus d'abord, puis tout texte libre (messages,
     * erreurs) qui citerait une valeur déjà connue.
     */
    public Object masquer(Object valeur) {
        enregistrer(valeur);
        return remplacer(valeur);
    }

    /** Texte libre (message de l'administrateur, phrase de SIGEP) où les valeurs connues sont masquées. */
    public String masquerTexte(String texte) {
        if (texte == null || texte.isEmpty() || pourTexte.isEmpty()) return texte;
        List<Map.Entry<String, String>> valeurs = new ArrayList<>(pourTexte.entrySet());
        valeurs.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        String resultat = texte;
        for (Map.Entry<String, String> e : valeurs) {
            Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}_@.])" + Pattern.quote(e.getKey()) + "(?![\\p{L}\\p{N}_])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            resultat = p.matcher(resultat).replaceAll(Matcher.quoteReplacement(e.getValue()));
        }
        return resultat;
    }

    /** Texte du modèle (réponse, argument d'outil) où chaque code redevient la vraie valeur. */
    public String retablir(String texte) {
        if (texte == null || texte.isEmpty()) return texte;
        Matcher m = CODE.matcher(texte);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String valeur = versValeur.get(m.group(1) + "_" + m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(valeur != null ? valeur : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Arguments d'un outil (objets, listes, textes) avec les codes rétablis. */
    @SuppressWarnings("unchecked")
    public Object retablirTout(Object valeur) {
        if (valeur instanceof String s) return retablir(s);
        if (valeur instanceof Map<?, ?> m) {
            Map<String, Object> copie = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> copie.put(k, retablirTout(v)));
            return copie;
        }
        if (valeur instanceof List<?> l) return l.stream().map(this::retablirTout).toList();
        return valeur;
    }

    private void enregistrer(Object valeur) {
        if (valeur instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                String genre = CHAMPS.get(String.valueOf(k));
                if (genre != null && v instanceof String s && !s.isBlank()) code(genre, s.trim());
                else enregistrer(v);
            });
        } else if (valeur instanceof List<?> l) {
            l.forEach(this::enregistrer);
        }
    }

    private Object remplacer(Object valeur) {
        if (valeur instanceof Map<?, ?> m) {
            Map<String, Object> copie = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                String genre = CHAMPS.get(String.valueOf(k));
                copie.put(String.valueOf(k), genre != null && v instanceof String s && !s.isBlank()
                        ? code(genre, s.trim()) : remplacer(v));
            });
            return copie;
        }
        if (valeur instanceof List<?> l) return l.stream().map(this::remplacer).toList();
        if (valeur instanceof String s) return masquerTexte(s);
        return valeur;
    }

    /** Pour les tests et le diagnostic : nombre de valeurs masquées. */
    public int taille() {
        return versValeur.size();
    }

    /** Champs traités, pour la documentation. */
    public static Set<String> champs() {
        return CHAMPS.keySet();
    }
}
