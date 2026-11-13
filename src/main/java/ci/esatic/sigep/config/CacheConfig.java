package ci.esatic.sigep.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.ClientOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Le cache de l'application : Redis quand il est provisionné ({@code CACHE_TYPE=redis}), sinon
 * une mémoire locale bornée. Les deux obéissent aux mêmes règles, écrites ici une seule fois.
 *
 * <h2>Les règles</h2>
 * <ul>
 *   <li><b>Tout cache est déclaré dans {@link #CACHES}</b>, avec sa durée et son type. Un nom
 *       inconnu fait échouer l'appel au lieu de créer un cache sans durée : une donnée qui
 *       n'expire jamais est une donnée fausse en attente.</li>
 *   <li><b>La clé porte l'établissement.</b> Une méthode mise en cache prend l'identifiant de
 *       l'établissement dans sa clé et ne met rien en cache quand il est absent — sinon les
 *       données d'un établissement seraient servies à un autre.</li>
 *   <li><b>Des valeurs simples, jamais d'entité ni de donnée personnelle.</b> On garde des
 *       compteurs et des agrégats. Chaque cache a son type, sérialisé en JSON : ni
 *       sérialisation Java, ni nom de classe dans la valeur, deux portes connues vers
 *       l'exécution de code à la relecture.</li>
 *   <li><b>Une écriture efface ce qu'elle périme</b> ({@code @CacheEvict}) ; la durée courte
 *       n'est que le filet, pour les changements que personne ne déclenche.</li>
 *   <li><b>Une panne du cache n'est jamais une panne de l'application.</b> Une erreur de
 *       lecture vaut une absence, et la donnée est relue en base ({@link #errorHandler}).
 *       Redis injoignable : les commandes échouent tout de suite au lieu d'attendre leur délai
 *       ({@link #echecImmediatSiRedisEstCoupe}).</li>
 * </ul>
 *
 * <p>Ce qui n'y est pas, exprès : l'analyse de la plateforme et la version du schéma sont
 * gardées en mémoire par leur service. Recalculables à l'identique sur chaque instance, elles
 * n'ont rien à gagner à voyager.
 */
@Configuration
@EnableCaching
@Slf4j
public class CacheConfig implements CachingConfigurer {

    /** Nombre d'alertes à traiter d'un établissement (badge de toutes les pages d'administration). */
    public static final String ALERTES = "alertes";

    /** Un cache : son nom, la durée de vie de ses entrées, le type de ses valeurs. */
    public record Declaration(String nom, Duration duree, Class<?> type) {}

    /** L'inventaire. Ajouter un cache, c'est ajouter une ligne ici — et nulle part ailleurs. */
    public static final List<Declaration> CACHES = List.of(
            new Declaration(ALERTES, Duration.ofSeconds(20), Long.class));

    /** Plafond d'entrées par cache local : une entrée par établissement, la marge est large. */
    private static final long ENTREES_MAX = 10_000;

    /**
     * Préfixe de toutes les clés. Il isole SIGEP sur un Redis partagé, et son numéro de version
     * permet de changer le format d'une valeur sans relire les anciennes : on l'incrémente, les
     * anciennes clés s'éteignent d'elles-mêmes.
     */
    @Value("${app.cache.prefixe:sigep:v1:}")
    private String prefixe;

    /** Redis : chaque cache déclaré reçoit sa durée, son préfixe et son sérialiseur typé. */
    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
    RedisCacheManagerBuilderCustomizer cachesRedis() {
        return constructeur -> {
            for (Declaration c : CACHES) {
                constructeur.withCacheConfiguration(c.nom(), RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(c.duree())
                        .disableCachingNullValues()
                        .computePrefixWith(nom -> prefixe + nom + ":")
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new JacksonJsonRedisSerializer<>(c.type()))));
            }
            constructeur.disableCreateOnMissingCache().enableStatistics();
        };
    }

    /** Sans Redis : la même liste de caches, en mémoire, avec les mêmes durées. */
    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "simple", matchIfMissing = true)
    CacheManager cachesLocaux() {
        CaffeineCacheManager gestionnaire = new CaffeineCacheManager();
        // Aucun cache créé à la volée : seuls ceux de l'inventaire existent. Fixé AVANT de les
        // enregistrer, car cet appel recrée les caches nommés sans durée de vie.
        gestionnaire.setCacheNames(CACHES.stream().map(Declaration::nom).toList());
        for (Declaration c : CACHES) {
            gestionnaire.registerCustomCache(c.nom(), Caffeine.newBuilder()
                    .expireAfterWrite(c.duree())
                    .maximumSize(ENTREES_MAX)
                    .recordStats()
                    .build());
        }
        return gestionnaire;
    }

    /**
     * Redis coupé : une commande échoue aussitôt au lieu d'attendre son délai. Sans cela, chaque
     * page attendrait une seconde par lecture de cache pendant toute la durée de la panne.
     */
    @Bean
    LettuceClientOptionsBuilderCustomizer echecImmediatSiRedisEstCoupe() {
        return options -> options
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    /**
     * Une erreur du cache ne remonte jamais jusqu'à l'utilisateur : la lecture est traitée comme
     * une absence et la méthode d'origine s'exécute. Le prix d'un effacement manqué est borné par
     * la durée de vie de l'entrée, d'où des durées courtes.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object cle) {
                signaler("lecture", cache, e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object cle, Object valeur) {
                signaler("écriture", cache, e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object cle) {
                signaler("effacement", cache, e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                signaler("vidage", cache, e);
            }
        };
    }

    private final AtomicLong dernierSignalement = new AtomicLong();

    /** Une ligne par minute au plus : une panne ne doit pas noyer le journal sous ses propres échos. */
    private void signaler(String operation, Cache cache, RuntimeException e) {
        long maintenant = System.currentTimeMillis();
        long precedent = dernierSignalement.get();
        if (maintenant - precedent >= 60_000 && dernierSignalement.compareAndSet(precedent, maintenant)) {
            log.warn("Cache « {} » indisponible ({}) : la donnée est relue en base. Cause : {}",
                    cache.getName(), operation, e.getMessage());
        }
    }
}
