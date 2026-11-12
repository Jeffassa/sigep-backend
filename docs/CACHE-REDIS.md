# Cache et Redis

SIGEP garde en cache quelques valeurs coûteuses à recalculer. Par défaut ce cache vit dans la
mémoire de l'application ; avec Redis, il est partagé entre les instances et survit aux
redémarrages. Le code est le même dans les deux cas : seule la configuration change.

## Mettre Redis en service

1. Créer une instance Redis dans la **même région** que l'application (sur Render : *Key Value*).
   Régler `maxmemory-policy` sur `allkeys-lru`, sans persistance.
2. Sur le service de l'application, définir :

   | Variable | Valeur |
   |---|---|
   | `SPRING_DATA_REDIS_URL` | l'adresse interne fournie par l'hébergeur (`rediss://…` si elle sort du réseau privé) |
   | `CACHE_TYPE` | `redis` |
   | `RATE_LIMIT_REDIS_ENABLED` | `true` (compteurs de limitation de débit partagés) |

3. Redéployer. Le bloc « État technique » du tableau de bord de la plateforme affiche alors
   « Cache : Redis répond ».

Pour revenir en arrière, repasser `CACHE_TYPE` à `simple` : rien n'est perdu, tout se recalcule.

## Ce qui est en cache

| Cache | Contenu | Durée | Effacé quand |
|---|---|---|---|
| `alertes` | nombre d'alertes à traiter d'un établissement | 20 s | une action est faite dans cet établissement |

Clé Redis : `sigep:v1:alertes:<id de l'établissement>`.

Ne sont **pas** dans Redis, exprès : l'analyse de la plateforme (5 min) et la version du schéma,
gardées en mémoire par leur service. Elles se recalculent à l'identique sur chaque instance.

## Les règles

Elles sont appliquées par `CacheConfig` et vérifiées par `CacheConfigTest`.

- **Tout cache est déclaré** dans `CacheConfig.CACHES`, avec sa durée et son type. Un nom non
  déclaré fait échouer l'appel : aucune clé sans durée de vie.
- **La clé porte l'établissement**, et rien n'est mis en cache quand il est absent.
- **Des valeurs simples** : compteurs et agrégats. Ni entité, ni donnée personnelle, ni
  sérialisation Java, ni nom de classe dans la valeur.
- **Une écriture efface ce qu'elle périme** (`@CacheEvict`). La durée courte n'est que le filet.
- **Une panne de Redis n'est pas une panne de SIGEP** : une erreur de cache vaut une absence et
  la donnée est relue en base. Redis coupé, les commandes échouent tout de suite au lieu
  d'attendre leur délai.
- **Changer le format d'une valeur** : incrémenter la version du préfixe (`CACHE_PREFIXE`,
  `sigep:v2:`). Les anciennes clés s'éteignent à l'expiration.

## Ajouter un cache

1. Ajouter une ligne à `CacheConfig.CACHES` (nom, durée, type de la valeur).
2. Annoter la méthode de lecture avec `@Cacheable`, clé = identifiant de l'établissement,
   condition = établissement présent.
3. Annoter ou appeler l'effacement sur **chaque** chemin qui modifie la donnée.
4. Compter les requêtes de la page avant et après : un cache qui n'en retire aucune ne sert à rien.
