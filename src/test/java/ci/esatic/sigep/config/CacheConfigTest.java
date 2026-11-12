package ci.esatic.sigep.config;

import ci.esatic.sigep.service.AlerteService;
import ci.esatic.sigep.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Les règles du cache, vérifiées sur le cache local — celui qui sert tant que Redis n'est pas
 * provisionné. Redis applique la même liste de caches et les mêmes durées.
 */
@SpringBootTest
@ActiveProfiles("test")
class CacheConfigTest {

    private static final Long ECOLE_A = 9_001L;
    private static final Long ECOLE_B = 9_002L;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private AlerteService alerteService;

    @AfterEach
    void nettoyer() {
        TenantContext.clear();
        cacheManager.getCache(CacheConfig.ALERTES).clear();
    }

    @Test
    void seulsLesCachesDeLInventaireExistent() {
        assertThat(cacheManager.getCacheNames())
                .containsExactlyInAnyOrderElementsOf(CacheConfig.CACHES.stream().map(CacheConfig.Declaration::nom).toList());
        // Un nom inconnu ne crée pas un cache à la volée, sans durée de vie.
        assertThat(cacheManager.getCache("cache-jamais-declare")).isNull();
    }

    @Test
    void chaqueCacheASaDureeDeVieEtUnPlafond() {
        for (CacheConfig.Declaration c : CacheConfig.CACHES) {
            var natif = ((CaffeineCache) cacheManager.getCache(c.nom())).getNativeCache();
            assertThat(natif.policy().expireAfterWrite())
                    .as("le cache %s doit expirer", c.nom()).isPresent()
                    .get().satisfies(p -> assertThat(p.getExpiresAfter()).isEqualTo(c.duree()));
            assertThat(natif.policy().eviction()).as("le cache %s doit être borné", c.nom()).isPresent();
            assertThat(c.duree()).as("une durée courte borne le prix d'un effacement manqué")
                    .isLessThanOrEqualTo(Duration.ofMinutes(10));
        }
    }

    @Test
    void leCompteurEstGardeParEtablissement_etJamaisPartage() {
        Cache cache = cacheManager.getCache(CacheConfig.ALERTES);

        TenantContext.set(ECOLE_A);
        long valeur = alerteService.compter();

        assertThat(cache.get(ECOLE_A)).as("gardé sous la clé de l'établissement").isNotNull();
        assertThat(cache.get(ECOLE_A).get()).isEqualTo(valeur);
        assertThat(cache.get(ECOLE_B)).as("rien n'est posé pour un autre établissement").isNull();

        // Une valeur posée pour un autre établissement ne lui est servie qu'à lui.
        cache.put(ECOLE_B, 777L);
        assertThat(alerteService.compter()).isEqualTo(valeur);
        TenantContext.set(ECOLE_B);
        assertThat(alerteService.compter()).isEqualTo(777L);
    }

    @Test
    void sansEtablissementRienNEstGarde() {
        Cache cache = cacheManager.getCache(CacheConfig.ALERTES);
        TenantContext.clear();

        alerteService.compter();

        var natif = ((CaffeineCache) cache).getNativeCache();
        assertThat(natif.asMap()).as("sans établissement, la clé serait commune à tous").isEmpty();
    }

    @Test
    void uneActionEffaceLeCompteurDeSonEtablissementSeulement() {
        Cache cache = cacheManager.getCache(CacheConfig.ALERTES);
        cache.put(ECOLE_A, 5L);
        cache.put(ECOLE_B, 9L);

        alerteService.oublier(ECOLE_A);

        assertThat(cache.get(ECOLE_A)).isNull();
        assertThat(cache.get(ECOLE_B)).isEqualTo(new SimpleValueWrapper(9L));
        assertThatCode(() -> alerteService.oublier(null)).doesNotThrowAnyException();
    }

    @Test
    void unePanneDuCacheNeRemontePasJusquALUtilisateur() {
        var gestionnaireDErreurs = new CacheConfig().errorHandler();
        Cache cache = cacheManager.getCache(CacheConfig.ALERTES);
        RuntimeException panne = new IllegalStateException("Redis injoignable");

        assertThatCode(() -> {
            gestionnaireDErreurs.handleCacheGetError(panne, cache, ECOLE_A);
            gestionnaireDErreurs.handleCachePutError(panne, cache, ECOLE_A, 1L);
            gestionnaireDErreurs.handleCacheEvictError(panne, cache, ECOLE_A);
            gestionnaireDErreurs.handleCacheClearError(panne, cache);
        }).doesNotThrowAnyException();
    }
}
