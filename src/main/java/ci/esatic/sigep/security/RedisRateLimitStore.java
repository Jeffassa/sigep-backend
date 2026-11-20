package ci.esatic.sigep.security;

import ci.esatic.sigep.config.DisjoncteurRedis;
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
 * <p>SECURITE/ROBUSTESSE : Redis indisponible, le filtre compte en mémoire locale. Une panne
 * Redis dégrade le partage entre instances, jamais la limitation ni la disponibilité.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.security.login-rate-limit.redis-enabled", havingValue = "true")
public class RedisRateLimitStore {

    private final StringRedisTemplate redis;
    private final DisjoncteurRedis disjoncteur;

    public RedisRateLimitStore(StringRedisTemplate redis, DisjoncteurRedis disjoncteur) {
        this.redis = redis;
        this.disjoncteur = disjoncteur;
    }

    /**
     * Enregistre un hit sur la fenêtre courante et indique si la clé dépasse son budget, ou
     * {@code null} si Redis ne répond pas : l'appelant compte alors en mémoire locale. Redis
     * écarté par le disjoncteur, on ne l'attend même pas.
     */
    public Boolean overLimit(String cle, int max, long windowMs) {
        if (!disjoncteur.autorise()) return null;
        try {
            long fenetre = System.currentTimeMillis() / windowMs;
            String k = "rl:" + cle + ":" + fenetre;
            Long count = redis.opsForValue().increment(k);
            if (count != null && count == 1L) {
                // Garde deux fenêtres puis expire : borne l'espace mémoire Redis.
                redis.expire(k, Duration.ofMillis(windowMs * 2));
            }
            disjoncteur.reussite();
            return count != null && count > max;
        } catch (Exception e) {
            disjoncteur.echec(e);
            return null;
        }
    }
}
