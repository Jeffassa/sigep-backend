# Déploiement en production — SIGEP

Stack : **Docker Compose** = application Spring Boot + **PostgreSQL** + **Caddy** (reverse proxy + HTTPS automatique).
Portable : fonctionne sur un VPS Linux, une machine on-premise, ou tout hôte Docker.

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
