package ci.esatic.sigep.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Primitives de sécurité partagées (une seule implémentation à auditer/maintenir). */
public final class SecurityUtils {

    /**
     * Secret provisoire d'un compte créé par l'administration.
     *
     * <p>Tiré de {@link java.security.SecureRandom} : 12 octets, soit 96 bits d'entropie, très
     * au-delà de ce qu'exige un secret à usage unique. Le préfixe « Sigep- » garantit la présence
     * d'une lettre, et l'alphabet base64-URL fournit presque toujours un chiffre — les deux
     * conditions imposées aux mots de passe. Il est destiné à être remplacé dès la première
     * connexion, ce que force {@code User.mustChangePassword}.
     */
    public static String genererMotDePasseProvisoire() {
        byte[] bytes = new byte[12];
        new java.security.SecureRandom().nextBytes(bytes);
        return "Sigep-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private SecurityUtils() {}

    /**
     * Comparaison de chaînes à TEMPS CONSTANT (évite les attaques temporelles sur clés/secrets).
     * Renvoie false si l'une des valeurs est nulle.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
