package ci.esatic.sigep.service;

import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Le réseau d'où l'écran d'émargement d'un établissement peut s'afficher.
 *
 * <p>Saisi dans l'administration : adresses IP publiques ou plages CIDR, séparées par des
 * virgules, des espaces ou des retours à la ligne. Vide, l'écran s'affiche partout où son cookie
 * l'autorise.
 */
public final class ReseauEcran {

    /** Une IP ou une plage CIDR littérale (aucun nom d'hôte, donc aucune résolution DNS). */
    private static final Pattern ENTREE = Pattern.compile("[0-9a-fA-F:.]{2,45}(/\\d{1,3})?");
    public static final int LONGUEUR_MAX = 500;

    private ReseauEcran() {}

    /** Les entrées lisibles de la saisie ; lève {@link IllegalArgumentException} sur la première invalide. */
    public static List<String> lire(String saisie) {
        if (saisie == null || saisie.isBlank()) return List.of();
        List<String> entrees = Arrays.stream(saisie.split("[,;\\s]+")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
        for (String e : entrees) {
            if (!ENTREE.matcher(e).matches()) throw new IllegalArgumentException(e);
            try {
                new IpAddressMatcher(e).matches(e.contains("/") ? e.substring(0, e.indexOf('/')) : e);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException(e);
            }
        }
        return entrees;
    }

    /** Vrai si aucun réseau n'est déclaré, ou si l'IP appartient à l'un d'eux. */
    public static boolean autorise(String reseaux, String ip) {
        List<String> entrees;
        try {
            entrees = lire(reseaux);
        } catch (IllegalArgumentException e) {
            return false; // saisie corrompue : on ferme plutôt que d'ouvrir
        }
        if (entrees.isEmpty()) return true;
        if (ip == null || !ENTREE.matcher(ip).matches()) return false;
        for (String e : entrees) {
            try {
                if (new IpAddressMatcher(e).matches(ip)) return true;
            } catch (RuntimeException ignored) {
                // IPv4 contre plage IPv6, ou l'inverse : simplement pas dans ce réseau.
            }
        }
        return false;
    }
}
