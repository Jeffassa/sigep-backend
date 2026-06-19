# Déploiement en production — SIGEP

Deux options d'hébergement :

- **Option B — Hébergement gratuit, sans VPS (Render + Neon)** → recommandée si tu ne veux pas gérer de serveur. Voir ci-dessous.
- **Option A — Auto-hébergement Docker** (VPS, on-premise) → stack `docker-compose.prod.yml` (app + PostgreSQL + Caddy/HTTPS). Sections numérotées plus bas.

---

# Option B — Hébergement gratuit (Render + Neon)

Aucun VPS à administrer. **Render** héberge l'application (build depuis le `Dockerfile`, HTTPS automatique y compris sur ton domaine), **Neon** fournit un PostgreSQL managé gratuit (sans expiration). Tu n'utilises **ni** `docker-compose.prod.yml` **ni** Caddy ici (Render gère le TLS et le routage) ; seuls le `Dockerfile` et le profil `prod` servent.

> ⚠️ **À savoir (plan gratuit)** : l'app se met **en veille après ~15 min d'inactivité** → le tout premier accès ensuite est **lent (~30–60 s)** le temps du redémarrage, et peut faire échouer la 1ʳᵉ requête du mobile. Solution : un **« keep-warm »** gratuit (UptimeRobot / cron-job.org) qui appelle `https://ton-domaine/actuator/health` toutes les 5 min. C'est le compromis du gratuit ; pour zéro veille il faut un plan payant (ou un petit VPS).

### B.1 — Base de données (Neon)
1. Crée un compte sur **neon.tech** → nouveau projet → base `sigep_db`.
2. Récupère la chaîne de connexion. Tu en déduis :
   - `DB_URL` = `jdbc:postgresql://<HOST>/<DB>?sslmode=require`
   - `DB_USERNAME` et `DB_PASSWORD` (fournis par Neon).

### B.2 — Application (Render)
1. Crée un compte sur **render.com** → **New → Web Service** → connecte le dépôt GitHub `sigep-backend`.
2. **Runtime : Docker** (Render détecte le `Dockerfile`). Plan : **Free**.
3. **Health Check Path** : `/actuator/health`.
4. **Environment** → ajoute les variables :

   | Variable | Valeur |
   |---|---|
   | `SPRING_PROFILES_ACTIVE` | `prod` |
   | `DB_URL` | la chaîne Neon (`...sslmode=require`) |
   | `DB_USERNAME` / `DB_PASSWORD` | identifiants Neon |
   | `JWT_SECRET` / `JWT_QR_SECRET` | `openssl rand -base64 48` (2 valeurs **différentes**) |
   | `APP_SECURITY_REQUIRE_HTTPS` | `true` |
   | `TRUST_FORWARDED_FOR` | `true` |
   | `ADMIN_EMAIL` | `admin@esatic.ci` |
   | `ADMIN_PASSWORD` | (vide = généré et affiché 1 fois dans les logs) |
   | `DB_POOL_MAX` | `5` *(adapté au tier gratuit)* |

5. **Deploy**. Au démarrage, **Flyway** crée le schéma (`refresh_tokens` incluse). Suis les logs pour récupérer le mot de passe admin si tu l'as laissé vide.

### B.3 — Ton nom de domaine (HTTPS automatique)
1. Dans Render → ton service → **Settings → Custom Domains** → ajoute `sigep.ton-domaine`.
2. Chez ton registrar, crée l'enregistrement **CNAME** indiqué par Render. Render émet le certificat TLS automatiquement (quelques minutes).

### B.4 — Garder l'app éveillée (optionnel mais conseillé)
- Compte gratuit **UptimeRobot** → monitor HTTP(s) sur `https://ton-domaine/actuator/health`, intervalle 5 min.

### B.5 — Vérifs
```bash
curl https://ton-domaine/actuator/health
curl -X POST https://ton-domaine/api/auth/login -H "Content-Type: application/json" \
  -d '{"email":"admin@esatic.ci","password":"MDP_ADMIN"}'
```

> Astuce : un fichier `render.yaml` (Blueprint) est fourni à la racine pour pré-remplir le service ; tu peux aussi tout faire depuis le tableau de bord comme ci-dessus.

---

# Option A — Auto-hébergement Docker (VPS / on-premise)

Stack : **Docker Compose** = application Spring Boot + **PostgreSQL** + **Caddy** (reverse proxy + HTTPS automatique).

---

## 1. Prérequis

- Un serveur **Linux** avec **Docker** et **Docker Compose v2** installés.
  ```bash
  curl -fsSL https://get.docker.com | sh
  ```
- Un **nom de domaine** (ex. `sigep.esatic.ci`) dont l'enregistrement **DNS A** pointe vers l'IP publique du serveur.
- Les **ports 80 et 443 ouverts** (HTTP/HTTPS) — nécessaires pour Let's Encrypt.

---

## 2. Récupérer le code et configurer l'environnement

```bash
git clone https://github.com/Jeffassa/sigep-backend.git
cd sigep-backend
cp .env.prod.example .env.prod
```

Éditer `.env.prod` et **renseigner tous les secrets**. Générer chaque clé avec :
```bash
openssl rand -base64 48
```
À remplir obligatoirement : `DOMAIN`, `LETSENCRYPT_EMAIL`, `DB_PASSWORD`, `JWT_SECRET`, `JWT_QR_SECRET`.
> ⚠️ `JWT_SECRET` ≠ `JWT_QR_SECRET`. Ne **jamais** committer `.env.prod` (déjà ignoré par `.gitignore`).

---

## 3. Lancer la stack

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

Au démarrage : **Flyway** applique automatiquement les migrations (`V1`→`V3`, dont la table `refresh_tokens`),
puis **Caddy** obtient le certificat TLS Let's Encrypt pour `DOMAIN` (quelques secondes).

Suivre les logs :
```bash
docker compose -f docker-compose.prod.yml logs -f app
```
> Si `ADMIN_PASSWORD` est vide, un mot de passe admin aléatoire est **affiché une fois** dans ces logs — notez-le et changez-le.

---

## 4. Vérifications

```bash
# Santé (doit répondre {"status":"UP"})
curl https://VOTRE_DOMAINE/actuator/health

# Login admin (doit renvoyer token + refreshToken)
curl -X POST https://VOTRE_DOMAINE/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@esatic.ci","password":"VOTRE_MDP_ADMIN"}'
```
Une requête en `http://` doit être **redirigée en `https://`** (assuré par Caddy + `require-https`).

---

## 5. Exploitation

**Sauvegarde de la base** (à planifier, ex. cron quotidien) :
```bash
docker compose -f docker-compose.prod.yml exec -T db \
  pg_dump -U "$DB_USERNAME" "$DB_NAME" | gzip > sigep_$(date +%F).sql.gz
```

**Mettre à jour l'application** :
```bash
git pull
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

**Arrêter** : `docker compose -f docker-compose.prod.yml down` (les données DB et certificats sont conservés dans les volumes).

---

## 6. Application mobile (build release)

Le backend de prod est en HTTPS ; l'appli doit pointer dessus et être signée.

1. **URL de l'API** — `app/src/main/java/ci/esatic/sigep/utils/Constants.kt` : faire pointer la build *release*
   vers `https://VOTRE_DOMAINE/` (idéalement via `buildConfigField` distinct debug/release — je peux le câbler).
2. **Désactiver le cleartext** en release (sécurité) : pas de HTTP en clair, uniquement HTTPS.
3. **Générer une clé de signature** (à conserver précieusement, hors Git) :
   ```bash
   keytool -genkey -v -keystore sigep-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias sigep
   ```
4. **Construire l'APK signé** :
   ```bash
   ./gradlew assembleRelease
   ```
   (configurer `signingConfigs` dans `app/build.gradle` avec le keystore, via des variables/`local.properties` non committées.)
5. **Distribuer** l'APK aux enseignants (lien de téléchargement interne), ou publier sur le Play Store.

---

## 7. Checklist sécurité prod

- [ ] `.env.prod` rempli avec des secrets **forts et uniques**, jamais committé.
- [ ] `APP_SECURITY_REQUIRE_HTTPS=true` et `TRUST_FORWARDED_FOR=true` (derrière Caddy).
- [ ] Accès SSH au serveur restreint ; pare-feu n'ouvrant que 22/80/443.
- [ ] Sauvegardes de la base testées (restauration vérifiée).
- [ ] Mot de passe admin initial changé.
- [ ] Keystore mobile sauvegardé hors ligne (sa perte empêche toute mise à jour de l'appli).
