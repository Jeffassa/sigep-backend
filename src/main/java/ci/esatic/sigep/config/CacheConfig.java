package ci.esatic.sigep.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Active l'abstraction de cache Spring (@Cacheable / @CacheEvict).
 *
 * <p>Le type de cache est piloté par {@code spring.cache.type} :
 * <ul>
 *   <li>{@code simple} (défaut) : cache mémoire local — aucune dépendance externe ;</li>
 *   <li>{@code redis} : cache DISTRIBUÉ (partagé entre instances, persistant) — activé en
 *       définissant {@code CACHE_TYPE=redis} + {@code REDIS_URL} (voir application.yml).</li>
 * </ul>
 *
 * <p>SECURITE MULTI-TENANT : toute méthode annotée {@code @Cacheable} DOIT inclure l'identifiant
 * du tenant dans sa clé (et se désactiver quand le tenant est absent), sinon des données d'un
 * établissement pourraient être servies à un autre. Aucun cache n'est appliqué aux données métier
 * dans cette configuration ; l'infrastructure est simplement prête à l'emploi.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
