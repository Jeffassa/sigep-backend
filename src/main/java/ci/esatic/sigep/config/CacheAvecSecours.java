package ci.esatic.sigep.config;

import org.springframework.cache.Cache;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Un cache Redis doublé d'une copie en mémoire locale, qui prend le relais quand le
 * {@link DisjoncteurRedis} écarte Redis.
 *
 * <p>Les écritures et les effacements vont aux deux : la copie locale est toujours à jour, et la
 * bascule ne sert pas de valeur périmée. Les lectures vont à Redis tant qu'il répond, à la copie
 * sinon. Une erreur de Redis n'est jamais propagée : elle ouvre le circuit, et la copie répond.
 */
final class CacheAvecSecours implements Cache {

    private final Cache redis;
    private final Cache secours;
    private final DisjoncteurRedis disjoncteur;

    CacheAvecSecours(Cache redis, Cache secours, DisjoncteurRedis disjoncteur) {
        this.redis = redis;
        this.secours = secours;
        this.disjoncteur = disjoncteur;
    }

    @Override
    public String getName() {
        return redis.getName();
    }

    @Override
    public Object getNativeCache() {
        return redis.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object cle) {
        return lire(() -> redis.get(cle), () -> secours.get(cle));
    }

    @Override
    public <T> T get(Object cle, Class<T> type) {
        return lire(() -> redis.get(cle, type), () -> secours.get(cle, type));
    }

    @Override
    public <T> T get(Object cle, Callable<T> chargeur) {
        // Le chargeur lit la base : il ne passe qu'une fois, par la copie locale, et sa propre
        // erreur remonte telle quelle au lieu d'être prise pour une panne de Redis.
        ValueWrapper v = get(cle);
        if (v != null) {
            @SuppressWarnings("unchecked") T valeur = (T) v.get();
            return valeur;
        }
        T valeur = secours.get(cle, chargeur);
        if (valeur != null) ecrire(() -> redis.put(cle, valeur));
        return valeur;
    }

    @Override
    public void put(Object cle, Object valeur) {
        secours.put(cle, valeur);
        ecrire(() -> redis.put(cle, valeur));
    }

    @Override
    public void evict(Object cle) {
        secours.evict(cle);
        ecrire(() -> redis.evict(cle));
    }

    @Override
    public void clear() {
        secours.clear();
        ecrire(redis::clear);
    }

    private <T> T lire(Supplier<T> depuisRedis, Supplier<T> depuisSecours) {
        if (disjoncteur.autorise()) {
            try {
                T valeur = depuisRedis.get();
                disjoncteur.reussite();
                return valeur;
            } catch (RuntimeException e) {
                disjoncteur.echec(e);
            }
        }
        return depuisSecours.get();
    }

    /**
     * Redis écarté : l'écriture est sautée. Un effacement manqué ne sert jamais de valeur
     * périmée, car la pause dépasse la durée de vie des entrées : au retour, elles ont expiré.
     */
    private void ecrire(Runnable versRedis) {
        if (!disjoncteur.autorise()) return;
        try {
            versRedis.run();
            disjoncteur.reussite();
        } catch (RuntimeException e) {
            disjoncteur.echec(e);
        }
    }
}
