package ci.esatic.sigep.security.sso;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Chiffrement des secrets qu'il faut pouvoir RELIRE.
 *
 * <p>Presque tout ce que nous gardons de secret est haché : un mot de passe, une clé d'API, un
 * code de secours. Hacher suffit parce qu'il n'y a jamais qu'à vérifier une correspondance. Le
 * secret client OIDC, lui, doit repartir en clair vers le fournisseur d'identité à chaque
 * connexion : il faut donc le chiffrer, et non le hacher.
 *
 * <p>AES-256-GCM. Le mode compte autant que l'algorithme : GCM authentifie le texte chiffré, si
 * bien qu'une ligne modifiée en base est refusée au déchiffrement au lieu de produire un secret
 * silencieusement faux — et d'envoyer au fournisseur d'identité une valeur qu'on croirait bonne.
 * Un vecteur d'initialisation aléatoire est tiré à chaque chiffrement et rangé en tête : deux
 * établissements qui emploieraient le même secret ne produisent pas la même ligne.
 *
 * <h2>La clé</h2>
 *
 * <p>{@code APP_SSO_SECRET_KEY}, 32 octets en base64, vit en variable d'environnement. Elle n'a
 * volontairement <b>pas de repli</b> : sans elle, le SSO ne se configure pas et refuse de
 * s'ouvrir. Une valeur par défaut versionnée reviendrait à publier la clé qui protège les secrets
 * clients de tous nos établissements.
 *
 * <p>Conséquence à connaître avant de l'adopter : cette clé perdue, les secrets déjà enregistrés
 * sont irrécupérables. Ils se ressaisissent — ce sont des valeurs que le fournisseur d'identité
 * réémet — mais chaque établissement devra le faire.
 */
@Component
@Slf4j
public class ChiffrementSecret {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int TAILLE_IV = 12;        // 96 bits : la taille recommandée pour GCM
    private static final int TAILLE_TAG_BITS = 128;
    private static final int TAILLE_CLE = 32;       // AES-256

    private final SecretKey cle;
    private final SecureRandom alea = new SecureRandom();

    public ChiffrementSecret(@Value("${app.sso.secret-key:}") String cleBase64) {
        this.cle = lire(cleBase64);
        if (this.cle == null && cleBase64 != null && !cleBase64.isBlank()) {
            // Une clé fournie mais inutilisable est un incident d'exploitation : sans ce
            // signal, le SSO se contenterait de « ne pas être disponible », et personne ne
            // saurait que la cause est une clé mal formée plutôt qu'une clé absente.
            log.error("SSO : APP_SSO_SECRET_KEY est présente mais inutilisable "
                    + "(attendu : 32 octets en base64). Le SSO reste indisponible.");
        }
    }

    private static SecretKey lire(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        try {
            // Les interfaces d'hébergeur invitent à coller la valeur entre guillemets, et un
            // copier-coller emporte volontiers un espace : aucun de ces accidents ne doit
            // transformer une clé valide en « SSO indisponible » inexpliqué.
            String net = base64.trim().replaceAll("^[\"']|[\"']$", "").trim();
            byte[] octets = Base64.getDecoder().decode(net);
            if (octets.length != TAILLE_CLE) return null;
            return new SecretKeySpec(octets, "AES");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Une clé exploitable est-elle installée ? Sans elle, le SSO ne se configure pas. */
    public boolean disponible() {
        return cle != null;
    }

    /**
     * Chiffre une valeur. Le résultat est transportable tel quel en base.
     *
     * @throws SsoIndisponibleException si aucune clé n'est installée
     */
    public String chiffrer(String clair) {
        exigerCle();
        try {
            byte[] iv = new byte[TAILLE_IV];
            alea.nextBytes(iv);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, cle, new GCMParameterSpec(TAILLE_TAG_BITS, iv));
            byte[] chiffre = c.doFinal(clair.getBytes(StandardCharsets.UTF_8));

            byte[] sortie = new byte[iv.length + chiffre.length];
            System.arraycopy(iv, 0, sortie, 0, iv.length);
            System.arraycopy(chiffre, 0, sortie, iv.length, chiffre.length);
            return Base64.getEncoder().encodeToString(sortie);
        } catch (Exception e) {
            // Le message de l'exception sous-jacente n'est pas remonté : il peut contenir des
            // éléments du texte traité. Le journal technique garde la trace, pas la valeur.
            log.error("SSO : chiffrement impossible ({})", e.getClass().getSimpleName());
            throw new SsoIndisponibleException("Le secret n'a pas pu être chiffré.");
        }
    }

    /**
     * Déchiffre une valeur produite par {@link #chiffrer}.
     *
     * @throws SsoIndisponibleException si la clé manque, ou si la ligne a été altérée
     */
    public String dechiffrer(String chiffreBase64) {
        exigerCle();
        try {
            byte[] tout = Base64.getDecoder().decode(chiffreBase64);
            if (tout.length <= TAILLE_IV) {
                throw new IllegalArgumentException("trop court");
            }
            byte[] iv = new byte[TAILLE_IV];
            System.arraycopy(tout, 0, iv, 0, TAILLE_IV);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, cle, new GCMParameterSpec(TAILLE_TAG_BITS, iv));
            return new String(c.doFinal(tout, TAILLE_IV, tout.length - TAILLE_IV),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Trois causes possibles, toutes graves et toutes traitées pareil : clé changée,
            // ligne altérée en base, ou valeur qui n'a jamais été chiffrée par nous. Dans les
            // trois cas, la connexion SSO doit s'arrêter là plutôt que de continuer avec un
            // secret qu'on n'a pas su relire.
            log.error("SSO : déchiffrement refusé ({}). Clé changée, ou ligne altérée.",
                    e.getClass().getSimpleName());
            throw new SsoIndisponibleException(
                    "Le secret enregistré n'a pas pu être relu. Ressaisissez-le.");
        }
    }

    private void exigerCle() {
        if (cle == null) {
            throw new SsoIndisponibleException(
                    "Le SSO n'est pas activé sur ce serveur : la clé de chiffrement "
                    + "APP_SSO_SECRET_KEY n'est pas installée.");
        }
    }
}
