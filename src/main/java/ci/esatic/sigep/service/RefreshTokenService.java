package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.RefreshToken;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.exception.RefreshTokenInvalidException;
import ci.esatic.sigep.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Gestion des refresh tokens : création, vérification, rotation, révocation.
 * SECURITE :
 *  - le token brut est aléatoire (256 bits) et n'est renvoyé qu'une fois au client ;
 *  - seule son empreinte SHA-256 est stockée ;
 *  - rotation systématique à chaque usage (l'ancien token est supprimé) ;
 *  - expiration et révocation contrôlées côté serveur.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.jwt.refresh-expiration}")
    private long refreshExpirationMs;

    /** Crée un refresh token, persiste son empreinte et renvoie le token brut (à transmettre au client). */
    @Transactional
    public String create(User user) {
        String raw = genererTokenBrut();
        RefreshToken entity = RefreshToken.builder()
                .tokenHash(hacher(raw))
                .user(user)
                .expiryDate(LocalDateTime.now().plus(Duration.ofMillis(refreshExpirationMs)))
                .revoked(false)
                .build();
        repository.save(entity);
        return raw;
    }

    /** Vérifie le refresh token (présent, non révoqué, non expiré). Lève {@link IllegalArgumentException} si invalide. */
    @Transactional(readOnly = true)
    public RefreshToken verifier(String rawToken) {
        RefreshToken rt = repository.findByTokenHash(hacher(rawToken))
                .orElseThrow(() -> new RefreshTokenInvalidException("Session invalide, veuillez vous reconnecter."));
        if (rt.isRevoked() || rt.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new RefreshTokenInvalidException("Session expirée, veuillez vous reconnecter.");
        }
        return rt;
    }

    /** Rotation : supprime l'ancien token et en émet un nouveau pour le même utilisateur. */
    @Transactional
    public String rotation(RefreshToken ancien) {
        User user = ancien.getUser();
        repository.delete(ancien);
        return create(user);
    }

    /** Révoque un refresh token précis (déconnexion). */
    @Transactional
    public void revoquer(String rawToken) {
        repository.findByTokenHash(hacher(rawToken)).ifPresent(repository::delete);
    }

    /** Révoque toutes les sessions d'un utilisateur. */
    @Transactional
    public void revoquerTout(User user) {
        repository.deleteByUser(user);
    }

    /** Purge quotidienne des tokens expirés. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgerExpires() {
        repository.deleteExpired(LocalDateTime.now());
    }

    private String genererTokenBrut() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hacher(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
