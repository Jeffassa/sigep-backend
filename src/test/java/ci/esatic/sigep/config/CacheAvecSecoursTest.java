package ci.esatic.sigep.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis qui ne répond plus ne doit coûter qu'une attente, pas une par lecture : le premier échec
 * l'écarte, la copie locale répond, puis une seule requête le reteste après la pause.
 */
class CacheAvecSecoursTest {

    /** Un Redis dont on règle la panne, et qui compte les appels qu'il reçoit. */
    static final class RedisSimule extends ConcurrentMapCache {
        volatile boolean enPanne;
        final AtomicInteger appels = new AtomicInteger();

        RedisSimule() { super("alertes", false); }

        private void appel() {
            appels.incrementAndGet();
            if (enPanne) throw new IllegalStateException("Connection initialization timed out after 1 second");
        }

        @Override public ValueWrapper get(Object cle) { appel(); return super.get(cle); }
        @Override public <T> T get(Object cle, Class<T> type) { appel(); return super.get(cle, type); }
        @Override public void put(Object cle, Object valeur) { appel(); super.put(cle, valeur); }
        @Override public void evict(Object cle) { appel(); super.evict(cle); }
        @Override public void clear() { appel(); super.clear(); }
    }

    private final AtomicLong maintenant = new AtomicLong(1_000_000);
    private RedisSimule redis;
    private DisjoncteurRedis disjoncteur;
    private Cache cache;

    @BeforeEach
    void setUp() {
        redis = new RedisSimule();
        disjoncteur = new DisjoncteurRedis(Duration.ofSeconds(30), maintenant::get);
        cache = new CacheAvecSecours(redis, new ConcurrentMapCache("alertes"), disjoncteur);
    }

    @Test
    void redisEnBonneSante_servitLesLecturesEtLesEcritures() {
        cache.put(1L, 5L);
        assertThat(redis.get(1L).get()).isEqualTo(5L);
        assertThat(cache.get(1L).get()).isEqualTo(5L);
        assertThat(disjoncteur.ouvert()).isFalse();
    }

    @Test
    void lePremierEchec_ecarteRedis_etLaCopieLocaleRepond() {
        cache.put(1L, 5L);
        redis.enPanne = true;

        assertThat(cache.get(1L).get()).as("la copie locale répond").isEqualTo(5L);
        assertThat(disjoncteur.ouvert()).isTrue();
        int appelsApresPanne = redis.appels.get();

        for (int i = 0; i < 50; i++) {
            cache.get(1L);
            cache.put(2L, 7L);
            cache.evict(3L);
        }
        assertThat(redis.appels.get()).as("plus aucune attente sur Redis pendant la pause").isEqualTo(appelsApresPanne);
        assertThat(cache.get(2L).get()).isEqualTo(7L);
    }

    @Test
    void unEffacementPendantLaPanne_nestPasPerduPourLaCopieLocale() {
        cache.put(1L, 5L);
        redis.enPanne = true;
        cache.get(1L);                      // ouvre le circuit

        cache.evict(1L);

        assertThat(cache.get(1L)).as("la valeur effacée n'est plus servie").isNull();
    }

    @Test
    void apresLaPause_uneSeuleRequeteReteste_etRedisReprendSaPlace() {
        redis.enPanne = true;
        cache.get(1L);
        assertThat(disjoncteur.ouvert()).isTrue();

        // Toujours en panne à la fin de la pause : un seul essai, puis une nouvelle pause.
        maintenant.addAndGet(Duration.ofSeconds(31).toMillis());
        int avant = redis.appels.get();
        cache.get(1L);
        cache.get(1L);
        assertThat(redis.appels.get() - avant).isEqualTo(1);
        assertThat(disjoncteur.ouvert()).isTrue();

        // Rétabli : le circuit se referme et Redis sert de nouveau.
        redis.enPanne = false;
        maintenant.addAndGet(Duration.ofSeconds(31).toMillis());
        cache.put(9L, 3L);
        assertThat(disjoncteur.ouvert()).isFalse();
        assertThat(redis.get(9L).get()).isEqualTo(3L);
    }

    @Test
    void uneErreurDuChargeur_remonteTelleQuelle_sansAccuserRedis() {
        assertThatThrownBy(() -> cache.get(1L, () -> { throw new IllegalArgumentException("base"); }))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(disjoncteur.ouvert()).isFalse();
    }
}
