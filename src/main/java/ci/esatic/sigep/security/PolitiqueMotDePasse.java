package ci.esatic.sigep.security;

import ci.esatic.sigep.exception.MetierException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Règles d'un mot de passe fort, les mêmes que celles que le formulaire d'inscription affiche
 * et coche au fil de la saisie : 10 caractères au moins, une minuscule, une majuscule, un chiffre,
 * un caractère spécial, ni l'adresse e-mail ni un mot de passe courant.
 */
public final class PolitiqueMotDePasse {

    public static final int LONGUEUR_MIN = 10;
    public static final int LONGUEUR_MAX = 128;

    /** Mots que l'on retrouve en tête des listes de mots de passe divulgués. */
    private static final List<String> COURANTS = List.of(
            "motdepasse", "password", "azerty", "qwerty", "123456", "abcdef", "sigep",
            "admin", "bienvenue", "welcome", "soleil", "iloveyou", "jetaime");

    private PolitiqueMotDePasse() {
    }

    /** Les règles non respectées, dans l'ordre du formulaire ; vide si le mot de passe est fort. */
    public static List<String> manquements(String motDePasse, String email) {
        List<String> m = new ArrayList<>();
        String mdp = motDePasse == null ? "" : motDePasse;
        if (mdp.length() < LONGUEUR_MIN) m.add("au moins " + LONGUEUR_MIN + " caractères");
        if (mdp.length() > LONGUEUR_MAX) m.add("au plus " + LONGUEUR_MAX + " caractères");
        if (mdp.chars().noneMatch(Character::isLowerCase)) m.add("une minuscule");
        if (mdp.chars().noneMatch(Character::isUpperCase)) m.add("une majuscule");
        if (mdp.chars().noneMatch(Character::isDigit)) m.add("un chiffre");
        if (mdp.chars().allMatch(Character::isLetterOrDigit)) m.add("un caractère spécial");

        String bas = mdp.toLowerCase(Locale.ROOT);
        String local = email == null ? "" : email.toLowerCase(Locale.ROOT).split("@", 2)[0];
        if (local.length() >= 4 && bas.contains(local)) m.add("pas votre adresse e-mail");
        if (COURANTS.stream().anyMatch(bas::contains)) m.add("pas un mot de passe courant");
        return m;
    }

    /** Refuse un mot de passe faible, en disant ce qui lui manque. */
    public static void exiger(String motDePasse, String email) {
        List<String> m = manquements(motDePasse, email);
        if (!m.isEmpty()) {
            throw new MetierException("MOT_DE_PASSE_FAIBLE",
                    "Mot de passe trop faible. Il faut : " + String.join(", ", m) + ".");
        }
    }
}
