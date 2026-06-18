package ci.esatic.sigep.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secretKey;

    @Value("${app.jwt.qr-secret}")
    private String qrSecretKey;

    @Value("${app.jwt.expiration}")
    private long jwtExpiration;

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return buildToken(extraClaims, userDetails, jwtExpiration);
    }

    public String generateQrToken(String salleCode, long expirationMs) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("salle", salleCode);
        claims.put("type", "QR");
        return Jwts.builder()
                .claims(claims)
                .subject(salleCode)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getQrSigningKey())
                .compact();
    }

    public boolean isQrTokenValid(String token, String salleCode) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getQrSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String tokenSalle = (String) claims.get("salle");
            String type = (String) claims.get("type");
            boolean expired = claims.getExpiration().before(new Date());
            return "QR".equals(type) && salleCode.equals(tokenSalle) && !expired;
        } catch (JwtException e) {
            return false;
        }
    }

    // ─── QR universel d'émargement ──────────────────────────────────────────
    // Un seul QR (affiché sur un écran), renouvelé toutes les 30 s. Il ne cible
    // aucune salle : il sert uniquement de preuve de présence au moment du scan.

    private static final String QR_UNIVERSAL_TYPE = "QR_UNIVERSAL";

    public String generateUniversalQrToken(long expirationMs) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", QR_UNIVERSAL_TYPE);
        return Jwts.builder()
                .claims(claims)
                .id(java.util.UUID.randomUUID().toString())   // jti : identifiant unique pour l'anti-rejeu
                .subject("SIGEP-EMARGEMENT")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getQrSigningKey())
                .compact();
    }

    public boolean isUniversalQrTokenValid(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getQrSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            boolean expired = claims.getExpiration().before(new Date());
            return QR_UNIVERSAL_TYPE.equals(claims.get("type")) && !expired;
        } catch (JwtException e) {
            return false;
        }
    }

    /** Identifiant unique (jti) du token QR universel, ou null si invalide. */
    public String extractQrJti(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getQrSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getId();
        } catch (JwtException e) {
            return null;
        }
    }

    private String buildToken(Map<String, Object> extraClaims, UserDetails userDetails, long expiration) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private SecretKey getQrSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(qrSecretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
