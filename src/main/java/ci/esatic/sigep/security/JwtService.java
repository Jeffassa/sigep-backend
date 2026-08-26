package ci.esatic.sigep.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Émission et lecture des JWT (access + QR). Un token n'est parsé/vérifié qu'UNE seule fois
 * par opération : les méthodes de lecture renvoient les Claims (ou un record) plutôt que de
 * re-parser pour chaque champ. Les clés HMAC sont décodées une fois au démarrage (immuables).
 */
@Service
public class JwtService {

    // Clés/valeurs de claims centralisées (évite les fautes de frappe entre génération et lecture).
    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_SALLE = "salle";
    private static final String CLAIM_ETAB = "etab";
    private static final String TYPE_QR_SALLE = "QR";
    private static final String TYPE_UNIVERSEL = "QR_UNIVERSAL";

    @Value("${app.jwt.secret}")
    private String secretKey;

    @Value("${app.jwt.qr-secret}")
    private String qrSecretKey;

    @Value("${app.jwt.expiration}")
    private long jwtExpiration;

    // Décodées et mémorisées à la 1re utilisation (immuables) — plus de reconstruction à chaque token.
    private SecretKey accessKey;
    private SecretKey qrKey;

    private SecretKey accessKey() {
        if (accessKey == null) accessKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey));
        return accessKey;
    }

    private SecretKey qrKey() {
        if (qrKey == null) qrKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(qrSecretKey));
        return qrKey;
    }

    // ─── Génération ──────────────────────────────────────────────────────────
    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(accessKey())
                .compact();
    }

    public String generateQrToken(String salleCode, long expirationMs) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_SALLE, salleCode);
        claims.put(CLAIM_TYPE, TYPE_QR_SALLE);
        return Jwts.builder()
                .claims(claims)
                .subject(salleCode)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(qrKey())
                .compact();
    }

    public String generateUniversalQrToken(long expirationMs, Long etablissementId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TYPE, TYPE_UNIVERSEL);
        // C3 : le QR est rattaché à un établissement (preuve de présence cloisonnée par tenant).
        if (etablissementId != null) claims.put(CLAIM_ETAB, etablissementId);
        return Jwts.builder()
                .claims(claims)
                .id(java.util.UUID.randomUUID().toString())   // jti : anti-rejeu
                .subject("SIGEP-EMARGEMENT")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(qrKey())
                .compact();
    }

    // ─── Access token : parsé UNE fois ─────────────────────────────────────────
    /** Claims de l'access token, ou {@code null} si signature/format invalide ou expiré non parsable. */
    public Claims parseAccessClaims(String token) {
        try {
            return parse(token, accessKey());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public String extractUsername(String token) {
        Claims claims = parseAccessClaims(token);
        return claims == null ? null : claims.getSubject();
    }

    /** Valide des Claims déjà parsés (pas de re-parsing) contre l'utilisateur. */
    public boolean isValid(Claims claims, UserDetails userDetails) {
        return claims != null
                && userDetails.getUsername().equals(claims.getSubject())
                && !estExpire(claims);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        return isValid(parseAccessClaims(token), userDetails);
    }

    // ─── QR par salle (chemin historique) ──────────────────────────────────────
    public boolean isQrTokenValid(String token, String salleCode) {
        try {
            Claims claims = parse(token, qrKey());
            return TYPE_QR_SALLE.equals(claims.get(CLAIM_TYPE))
                    && salleCode.equals(claims.get(CLAIM_SALLE))
                    && !estExpire(claims);
        } catch (JwtException e) {
            return false;
        }
    }

    // ─── QR universel d'émargement : parsé UNE fois → record ────────────────────
    /** Lecture unique du QR universel : validité (type + fraîcheur), établissement, jti. */
    public QrUniversel lireQrUniversel(String token) {
        try {
            Claims claims = parse(token, qrKey());
            boolean valide = TYPE_UNIVERSEL.equals(claims.get(CLAIM_TYPE)) && !estExpire(claims);
            Object etab = claims.get(CLAIM_ETAB);
            Long etablissementId = (etab instanceof Number n) ? n.longValue() : null;
            return new QrUniversel(valide, etablissementId, claims.getId());
        } catch (JwtException e) {
            return QrUniversel.invalide();
        }
    }

    /** Résultat de lecture d'un QR universel (une seule vérification de signature). */
    public record QrUniversel(boolean valide, Long etablissementId, String jti) {
        public static QrUniversel invalide() {
            return new QrUniversel(false, null, null);
        }
    }

    // ─── QR universel pour la synchro HORS-LIGNE : signature vérifiée, expiration TOLÉRÉE ──────
    /**
     * Lecture d'un QR universel SANS exiger la fraîcheur (exp), pour un émargement hors-ligne
     * synchronisé plus tard. La signature reste vérifiée (une signature invalide → {@code invalide()}),
     * ce qui prouve qu'un vrai QR de kiosque a été scanné. On renvoie l'instant d'émission signé
     * ({@code iat}) afin de borner l'écart avec la fenêtre de la séance (preuve de présence différée).
     */
    public QrUniverselDiffere lireQrUniverselDiffere(String token) {
        try {
            return depuisClaims(parse(token, qrKey()));
        } catch (ExpiredJwtException e) {
            // La signature est déjà vérifiée avant le contrôle d'expiration : claims exploitables.
            return depuisClaims(e.getClaims());
        } catch (JwtException | IllegalArgumentException e) {
            return QrUniverselDiffere.invalide();
        }
    }

    private QrUniverselDiffere depuisClaims(Claims claims) {
        boolean typeOk = TYPE_UNIVERSEL.equals(claims.get(CLAIM_TYPE));
        Object etab = claims.get(CLAIM_ETAB);
        Long etablissementId = (etab instanceof Number n) ? n.longValue() : null;
        Instant emisLe = claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant();
        return new QrUniverselDiffere(typeOk, etablissementId, claims.getId(), emisLe);
    }

    /**
     * QR universel lu en mode différé. {@code signatureEtTypeValides} = signature OK ET type QR universel ;
     * {@code emisLe} = instant d'émission signé par le kiosque (horodatage de confiance serveur).
     */
    public record QrUniverselDiffere(boolean signatureEtTypeValides, Long etablissementId,
                                     String jti, Instant emisLe) {
        public static QrUniverselDiffere invalide() {
            return new QrUniverselDiffere(false, null, null, null);
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────
    private Claims parse(String token, SecretKey key) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    private boolean estExpire(Claims claims) {
        Date expiration = claims.getExpiration();
        return expiration != null && expiration.before(new Date());
    }
}
