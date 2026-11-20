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
 *  - rotation systématique à chaque usage : l'ancien jeton est gardé, marqué « remplacé » ;
 *  - un jeton remplacé présenté une seconde fois prouve qu'il en existe une copie : toutes les
 *    sessions du compte sont fermées et l'évènement est consigné au journal de sécurité ;
 *  - expiration et révocation contrôlées côté serveur.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final ci.esatic.sigep.repository.UserRepository userRepository;
    private final JournalSecuriteService journalSecurite;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.jwt.refresh-expiration}")
    private long refreshExpirationMs;

    /** Crée un refresh token, persiste son empreinte et renvoie le token brut (à transmettre au client). */
    @Transactional
    public String create(User user) {
        return emettre(user).brut();
    }

    private record Emis(String brut, RefreshToken entite) {}

    private Emis emettre(User user) {
        String raw = genererTokenBrut();
        RefreshToken entity = RefreshToken.builder()
                .tokenHash(hacher(raw))
                .user(user)
                .expiryDate(LocalDateTime.now().plus(Duration.ofMillis(refreshExpirationMs)))
                .revoked(false)
                .build();
        return new Emis(raw, repository.save(entity));
    }

    /**
     * Vérifie le refresh token (présent, non remplacé, non révoqué, non expiré).
     *
     * <p>Un jeton déjà remplacé ne peut revenir que d'une copie : le client légitime, lui, détient
     * le successeur. On ne sait pas lequel des deux détenteurs est l'intrus, donc on ferme toutes
     * les sessions du compte ; le propriétaire se reconnecte avec son mot de passe, l'intrus non.
     */
    @Transactional(noRollbackFor = RefreshTokenInvalidException.class)
    public RefreshToken verifier(String rawToken) {
        RefreshToken rt = repository.findByTokenHash(hacher(rawToken))
                .orElseThrow(() -> new RefreshTokenInvalidException("Session invalide, veuillez vous reconnecter."));
        if (rt.getRemplacePar() != null) {
            fermerSessionsApresCopie(rt.getUser());
            throw new RefreshTokenInvalidException("Session fermée par sécurité, veuillez vous reconnecter.");
        }
        if (rt.isRevoked() || rt.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new RefreshTokenInvalidException("Session expirée, veuillez vous reconnecter.");
        }
        return rt;
    }

    /** L'exception qui suit n'annule pas la fermeture : {@code noRollbackFor} ici et chez l'appelant. */
    private void fermerSessionsApresCopie(User user) {
        repository.deleteByUser(user);
        journalSecurite.enregistrer(ci.esatic.sigep.entity.TypeEvenement.SESSION_COPIEE,
                ci.esatic.sigep.entity.SeveriteEvenement.CRITIQUE, null, user.getEmail(), "/api/auth/refresh",
                "Jeton de session déjà utilisé présenté de nouveau : toutes les sessions du compte sont fermées",
                user.getEtablissement() == null ? null : user.getEtablissement().getId());
    }

    /** Rotation : l'ancien jeton est marqué remplacé, et un nouveau est émis pour le même utilisateur. */
    @Transactional
    public String rotation(RefreshToken ancien) {
        Emis nouveau = emettre(ancien.getUser());
        ancien.setRemplacePar(nouveau.entite().getId());
        ancien.setRemplaceLe(LocalDateTime.now());
        repository.save(ancien);
        return nouveau.brut();
    }

    /** Révoque un refresh token précis (déconnexion). */
    @Transactional
    public void revoquer(String rawToken) {
        repository.findByTokenHash(hacher(rawToken)).ifPresent(repository::delete);
    }

    /**
     * Révoque les sessions de TOUS les comptes d'un établissement.
     *
     * <p>Levier de remédiation : après une fuite d'identifiants ou un poste compromis, couper une
     * session à la fois ne sert à rien — il faut fermer la maison. Chacun devra se reconnecter.
     *
     * @return nombre de comptes dont les sessions ont été coupées
     */
    @Transactional
    public int revoquerToutEtablissement(Long etablissementId) {
        var comptes = userRepository.findByEtablissementIdOrderByIdAsc(etablissementId);
        comptes.forEach(repository::deleteByUser);
        return comptes.size();
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
