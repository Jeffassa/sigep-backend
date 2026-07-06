# Sécurité — Rate-limiting & protection anti-abus / DDoS

Deux couches complémentaires : **applicative** (ce dépôt) et **réseau** (Cloudflare, en amont).

> ⚠️ La couche applicative bloque l'**abus** (bruteforce, spam d'inscription, scraping, floods
> ciblés). Elle **ne protège pas** d'un **DDoS volumétrique réseau** — surtout sur une instance
> unique Render gratuite, trivialement saturée. Le vrai bouclier DDoS est **Cloudflare** (§3).

---

## 1. Couche applicative (déjà en place)

### `RateLimitFilter` — limiteur par IP (fenêtre glissante 1 min, réponse HTTP 429)

**Budgets stricts** sur les POST sensibles :

| Endpoint | Défaut | Rôle |
|---|---|---|
| `POST /api/auth/login` | 5 / min | anti-bruteforce d'identifiants |
| `POST /api/auth/refresh` | 30 / min | large (refresh légitime fréquent, token à haute entropie) |
| `POST /api/saas/etablissements` | 5 / min | anti-spam d'inscription (public) |
| `POST /admin-login` | 10 / min | anti-bruteforce du back-office |

**Budget global** par IP, toutes routes confondues : **300 / min** (généreux car plusieurs
utilisateurs partagent souvent une IP — NAT d'établissement). **Exclusions** (fort trafic
légitime) : ressources statiques, `/actuator/health`, et `/api/qr/display/**` (écran kiosque
rafraîchi en continu).

**Mémoire bornée** : purge planifiée chaque minute + plafond de clés (50 000). Le limiteur ne
peut pas devenir lui-même un vecteur d'épuisement mémoire sous rotation d'IP.

### Durcissement serveur (Tomcat / HTTP)

- `max-connections` 2000, `accept-count` 100, `threads.max` 100 → échoue vite plutôt que d'empiler.
- `connection-timeout` 10 s → anti-slowloris (headers lents).
- `max-http-form-post-size` 2 Mo, `max-swallow-size` 2 Mo, `max-http-request-header-size` 16 Ko.
- Uploads (import Excel) : `max-file-size` 10 Mo, `max-request-size` 12 Mo → anti-épuisement mémoire.

---

## 2. Réglage par variables d'environnement (Render)

Tout est surchargeable sans redéploiement de code.

| Variable | Défaut | Effet |
|---|---|---|
| `LOGIN_RATE_LIMIT_ENABLED` | `true` | active/désactive tout le limiteur |
| `LOGIN_RATE_LIMIT_MAX` | `5` | budget login/min |
| `REFRESH_RATE_LIMIT_MAX` | `30` | budget refresh/min |
| `SIGNUP_RATE_LIMIT_MAX` | `5` | budget inscription/min |
| `ADMIN_LOGIN_RATE_LIMIT_MAX` | `10` | budget login admin/min |
| `RATE_LIMIT_MAX_GLOBAL` | `300` | budget global/IP/min (`0` = désactivé) |
| `RATE_LIMIT_MAX_KEYS` | `50000` | plafond mémoire du limiteur |
| `TRUST_FORWARDED_FOR` | `false` | **passer à `true` une fois derrière Cloudflare** (cf. §3) |
| `TOMCAT_MAX_CONNECTIONS` | `2000` | connexions simultanées max |
| `TOMCAT_ACCEPT_COUNT` | `100` | file d'attente de connexions |
| `TOMCAT_MAX_THREADS` | `100` | threads de traitement |
| `TOMCAT_CONNECTION_TIMEOUT` | `10s` | délai réception headers |

En cas de faux positifs (grand établissement derrière un seul NAT), augmenter
`RATE_LIMIT_MAX_GLOBAL` (ex. `600`) plutôt que de le désactiver.

---

## 3. Cloudflare — le bouclier DDoS (recommandé, gratuit)

C'est **la** vraie protection contre les attaques volumétriques. Cloudflare place son réseau
mondial devant `sigep.store` : absorption L3/L4, WAF, rate-limiting au bord, anti-bot, cache.

### Mise en place (une fois)
1. Créer un compte sur **cloudflare.com** (plan **Free**).
2. **Add a site** → `sigep.store` → plan **Free**.
3. Cloudflare importe les DNS existants. Vérifier l'enregistrement qui pointe vers Render
   (A ou CNAME) et s'assurer qu'il est **Proxied** (nuage **orange**, pas gris).
4. Chez le **registrar** du domaine, remplacer les **nameservers** par ceux fournis par
   Cloudflare. Activation en quelques minutes à quelques heures.
5. **SSL/TLS** → mode **Full (strict)**.
6. **SSL/TLS → Edge Certificates** → activer **Always Use HTTPS**.

### Protections à activer
7. **Security → WAF → Rate limiting rules** : créer 1 règle (le Free en autorise une), ex.
   « plus de **100 requêtes / 10 s** par IP » → action **Block** (ou **Managed Challenge**).
8. **Security → Bots** → activer **Bot Fight Mode**.
9. **Security → Settings** → **Under Attack Mode** : à **activer manuellement pendant une
   attaque** (challenge JavaScript pour chaque visiteur ; à désactiver ensuite).
10. **Rules → Managed rules / WAF** : laisser le jeu de règles managé par défaut.
11. **Caching** : le statique (`/css`, `/js`, `/images`) est mis en cache au bord — allège l'origine.

### Après activation de Cloudflare
- Sur **Render**, poser `TRUST_FORWARDED_FOR=true` : le limiteur applicatif comptera alors la
  **vraie IP** cliente (transmise par Cloudflare) et non l'IP du proxy.
- **Limite du free tier** : si l'URL d'origine Render reste joignable en direct, un attaquant
  peut contourner Cloudflare **et** falsifier `X-Forwarded-For`. Atténuations : garder l'URL
  `*.onrender.com` discrète, et à terme migrer vers un hébergement permettant de **n'autoriser
  que les IP Cloudflare** en entrée (liste publiée par Cloudflare). À traiter avant les premiers
  clients payants (cf. sortie du palier gratuit).

---

## 4. Vérifier rapidement

```bash
# Doit renvoyer 429 après le budget (ex. login : 6e tentative en < 1 min)
for i in $(seq 1 6); do \
  curl -s -o /dev/null -w "%{http_code}\n" -X POST https://sigep.store/api/auth/login \
    -H "Content-Type: application/json" -d '{"email":"x@y.z","password":"faux"}'; \
done
```
