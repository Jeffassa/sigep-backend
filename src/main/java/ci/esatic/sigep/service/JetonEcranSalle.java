package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

/**
 * Le cookie qui autorise un appareil à afficher le QR d'émargement d'un établissement.
 *
 * <p>La clé de l'écran ne passe plus par l'adresse de la page, où elle restait lisible dans la
 * barre d'adresse et l'historique de l'ordinateur de la salle. L'appareil reçoit à la place un
 * cookie inaccessible aux scripts, limité aux pages d'affichage du QR.
 *
 * <p>Le cookie ne contient pas la clé : c'est un HMAC de l'établissement et de sa clé, avec le
 * secret du serveur. Régénérer la clé depuis l'administration invalide donc tous les écrans.
 */
@Component
public class JetonEcranSalle {

    public static final String COOKIE = "sigep_ecran";
    /** Le cookie n'est envoyé qu'aux pages d'affichage du QR, nulle part ailleurs. */
    static final String CHEMIN = "/api/qr/display";
    /** Un écran de salle reste allumé toute l'année ; la révocation passe par la clé. */
    static final Duration DUREE = Duration.ofDays(365);
    private static final String HMAC = "HmacSHA256";

    private final byte[] cle;

    public JetonEcranSalle(@Value("${app.jwt.secret}") String secret) {
        // Clé dérivée, propre à cet usage : ce jeton ne vaut pour rien d'autre.
        this.cle = ("ecran-salle:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    /** Le cookie à poser sur l'appareil qui affichera le QR de cet établissement. */
    public ResponseCookie cookie(Etablissement e, boolean securise) {
        return ResponseCookie.from(COOKIE, signer(e))
                .httpOnly(true).secure(securise).sameSite("Lax")
                .path(CHEMIN).maxAge(DUREE).build();
    }

    /** Vrai si le jeton a été émis pour cet établissement avec sa clé actuelle. */
    public boolean valide(Etablissement e, String jeton) {
        if (e == null || jeton == null || jeton.length() > 100
                || e.getKioskKey() == null || e.getKioskKey().isBlank()) return false;
        return MessageDigest.isEqual(signer(e).getBytes(StandardCharsets.US_ASCII),
                jeton.getBytes(StandardCharsets.US_ASCII));
    }

    private String signer(Etablissement e) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(cle, HMAC));
            byte[] sig = mac.doFinal((e.getId() + ":" + e.getKioskKey()).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception ex) {
            throw new IllegalStateException("Signature de l'écran de salle impossible", ex);
        }
    }
}
