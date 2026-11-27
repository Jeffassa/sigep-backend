package ci.esatic.sigep.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Le jeton qui ouvre la page de paiement d'une inscription.
 *
 * <p>Juste après son inscription, le client n'a pas encore d'espace actif, donc pas de connexion :
 * c'est ce jeton, signé par le serveur et envoyé aussi par e-mail, qui lui donne accès au
 * paiement de SON établissement, et d'aucun autre. Il n'ouvre rien d'autre : ni l'espace, ni les
 * données de l'établissement.
 *
 * <p>Forme : {@code idEtablissement.expiration.signature}, la signature étant un HMAC-SHA256 des
 * deux premières parties avec le secret du serveur. Changer l'identifiant ou la date invalide
 * la signature.
 */
@Component
public class JetonPaiementInscription {

    /** Assez long pour revenir payer plus tard depuis l'e-mail. */
    static final Duration VALIDITE = Duration.ofDays(30);
    private static final String HMAC = "HmacSHA256";

    private final byte[] cle;

    public JetonPaiementInscription(@Value("${app.jwt.secret}") String secret) {
        // Clé dérivée, propre à cet usage : un jeton de paiement ne peut pas servir ailleurs.
        this.cle = ("paiement-inscription:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    public String creer(Long etablissementId) {
        return creer(etablissementId, Instant.now().plus(VALIDITE));
    }

    String creer(Long etablissementId, Instant expiration) {
        String corps = etablissementId + "." + expiration.getEpochSecond();
        return corps + "." + signer(corps);
    }

    /** L'établissement que le jeton désigne, s'il est intact et encore valable. */
    public Optional<Long> lire(String jeton) {
        if (jeton == null || jeton.length() > 200) return Optional.empty();
        String[] parties = jeton.split("\\.");
        if (parties.length != 3) return Optional.empty();
        String corps = parties[0] + "." + parties[1];
        byte[] attendue = signer(corps).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(attendue, parties[2].getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        try {
            long expiration = Long.parseLong(parties[1]);
            if (Instant.now().getEpochSecond() > expiration) return Optional.empty();
            return Optional.of(Long.parseLong(parties[0]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String signer(String corps) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(cle, HMAC));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(corps.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Signature du jeton de paiement impossible", e);
        }
    }
}
