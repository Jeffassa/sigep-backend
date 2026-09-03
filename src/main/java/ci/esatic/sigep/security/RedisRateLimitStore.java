package ci.esatic.sigep.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Compteur de rate-limit DISTRIBUÉ (Redis) — partagé entre instances et persistant aux
 * redémarrages, contrairement au compteur mémoire local du {@link RateLimitFilter}.
 *
 * <p>Créé UNIQUEMENT si {@code app.security.login-rate-limit.redis-enabled=true} (donc quand
 * Redis est provisionné). Fenêtre fixe par tranche de {@code windowMs} : {@code INCR} atomique
 * + {@code EXPIRE} à la première incrémentation.
 *
 * <p>SECURITE/ROBUSTESSE : <b>fail-open</b> en cas d'erreur Redis (on ne bloque pas le trafic
 * légitime si le cache est indisponible ; le budget global mémoire et les protections Tomcat
 * restent des filets). Une panne Redis dégrade la précision du rate-limit, jamais la disponibilité.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.security.login-rate-limit.redis-enabled", havingValue = "true")
public class RedisRateLimitStore {

    private final StringRedisTemplate redis;

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Enregistre un hit sur la fenêtre courante et indique si la clé dépasse son budget. */
    public boolean overLimit(String cle, int max, long windowMs) {
        try {
            long fenetre = System.currentTimeMillis() / windowMs;
            String k = "rl:" + cle + ":" + fenetre;
            Long count = redis.opsForValue().increment(k);
            if (count != null && count == 1L) {
                // Garde deux fenêtres puis expire : borne l'espace mémoire Redis.
                redis.expire(k, Duration.ofMillis(windowMs * 2));
            }
            return count != null && count > max;
        } catch (Exception e) {
            log.warn("Rate-limit Redis indisponible (fail-open) : {}", e.getMessage());
            return false;
        }
    }
}
