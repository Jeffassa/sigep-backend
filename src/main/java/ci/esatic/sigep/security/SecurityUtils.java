package ci.esatic.sigep.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Primitives de sécurité partagées (une seule implémentation à auditer/maintenir). */
public final class SecurityUtils {

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
