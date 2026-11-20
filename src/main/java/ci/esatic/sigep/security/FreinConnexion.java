package ci.esatic.sigep.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Frein aux essais de mot de passe sur un même compte, quelle que soit l'adresse IP d'origine.
 *
 * <p>La limitation par IP ne voit pas une attaque répartie sur plusieurs adresses. Ici, au-delà de
 * {@link #ESSAIS_MAX} échecs en {@link #FENETRE}, le compte refuse toute connexion, même avec le
 * bon mot de passe, jusqu'à la fin de la fenêtre. Le compteur repart de zéro à chaque connexion
 * réussie. Une adresse inconnue est comptée comme une autre : la réponse ne révèle pas si le
 * compte existe.
 */
@Component
public class FreinConnexion {

    static final int ESSAIS_MAX = 10;
    static final Duration FENETRE = Duration.ofMinutes(15);

    private final Cache<String, AtomicInteger> echecs = Caffeine.newBuilder()
            .expireAfterWrite(FENETRE)
            .maximumSize(50_000)
            .build();

    /** Lève {@link CompteFreineException} si le compte a épuisé ses essais. */
    public void verifier(String identifiant) {
        String cle = cle(identifiant);
        if (cle == null) return;
        AtomicInteger n = echecs.getIfPresent(cle);
        if (n != null && n.get() >= ESSAIS_MAX) {
            throw new CompteFreineException();
        }
    }

    public void echec(String identifiant) {
        String cle = cle(identifiant);
        if (cle == null) return;
        // Réécrit à chaque échec : la fenêtre court depuis le DERNIER essai.
        AtomicInteger n = echecs.get(cle, k -> new AtomicInteger());
        n.incrementAndGet();
        echecs.put(cle, n);
    }

    public void succes(String identifiant) {
        String cle = cle(identifiant);
        if (cle != null) echecs.invalidate(cle);
    }

    private static String cle(String identifiant) {
        if (identifiant == null || identifiant.isBlank()) return null;
        return identifiant.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Compte momentanément fermé aux connexions. Un état de compte, et non un simple échec :
     * le gestionnaire d'authentification le remonte tel quel au lieu d'essayer un autre fournisseur.
     */
    public static class CompteFreineException extends LockedException {
        public CompteFreineException() {
            super("Trop de tentatives sur ce compte. Réessayez dans 15 minutes.");
        }
    }
}
