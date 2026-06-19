# SIGEP — Backend

**Système Intelligent de Gestion des Enseignements** — ESATIC.
API REST + interface d'administration web pour le suivi des enseignements et l'émargement des enseignants.

---

## Sommaire
1. [Stack technique](#1-stack-technique)
2. [Prérequis](#2-prérequis)
3. [Démarrage rapide (dev local)](#3-démarrage-rapide-dev-local)
4. [Variables d'environnement](#4-variables-denvironnement)
5. [Profils Spring (dev / prod / test)](#5-profils-spring)
6. [Base de données & migrations (Flyway)](#6-base-de-données--migrations-flyway)
7. [Sécurité & authentification](#7-sécurité--authentification)
8. [Émargement & QR code](#8-émargement--qr-code)
9. [Imports Excel/CSV](#9-imports-excelcsv)
10. [Messagerie e-mail (Resend)](#10-messagerie-e-mail-resend)
11. [Observabilité (Actuator)](#11-observabilité-actuator)
12. [Tests](#12-tests)
13. [Déploiement en production](#13-déploiement-en-production)
14. [Dépannage (pièges connus)](#14-dépannage-pièges-connus)

---

## 1. Stack technique
- **Java 21**, **Spring Boot 3.3.5** (Maven, wrapper `mvnw` inclus)
- **Spring Security** — 2 chaînes : web admin (form login + CSRF) et API (JWT stateless)
- **JWT** (jjwt) — access token court + **refresh token** (rotation, révocable)
- **PostgreSQL** + JPA/Hibernate, **Flyway** (migrations)
- **Thymeleaf** (interface admin, Tailwind via CDN)
- **OpenPDF** (rapports PDF), **Apache POI** (imports Excel), **ZXing** (QR)
- **Spring Mail** (notifications via SMTP/Resend), **Actuator** (supervision)

## 2. Prérequis
- **JDK 21**
- **PostgreSQL 16+** (ou Docker)
- Maven : utiliser le wrapper fourni (`./mvnw`)

## 3. Démarrage rapide (dev local)

```bash
# 1. Cloner
git clone https://github.com/Jeffassa/sigep-backend.git
cd sigep-backend

# 2. Base de données (option Docker — PostgreSQL seul)
docker compose up -d          # PostgreSQL sur localhost:5432 (voir docker-compose.yml)
# … ou utiliser une instance PostgreSQL existante (base "sigep_db")

# 3. Lancer l'application (profil "dev" actif par défaut)
./mvnw spring-boot:run
```

- Interface admin : **http://localhost:8080/admin-login**
- Compte admin de dev : `admin@esatic.ci` / `Admin@2026` (`ADMIN_EMAIL` / `ADMIN_PASSWORD`)
- En **dev**, les secrets (`JWT_SECRET`, `JWT_QR_SECRET`, mot de passe DB) ont des valeurs par défaut — **aucune** configuration n'est requise pour démarrer.

> En **production**, ces valeurs par défaut n'existent pas : toutes les variables sensibles sont **obligatoires** (voir §4 et §13).

## 4. Variables d'environnement
Modèles fournis : **`.env.example`** (dev) et **`.env.prod.example`** (prod). Ne jamais committer un `.env` rempli.

| Variable | Obligatoire (prod) | Défaut (dev) | Rôle |
|---|---|---|---|
| `DB_URL` | — | `jdbc:postgresql://localhost:5432/sigep_db` | URL JDBC PostgreSQL |
| `DB_USERNAME` | — | `postgres` | Utilisateur DB |
| `DB_PASSWORD` | ✅ | `sigep_secret_password` | Mot de passe DB |
| `DB_POOL_MAX` | — | `25` | Taille max du pool Hikari |
| `JWT_SECRET` | ✅ | *(clé de dev)* | Signature des access tokens (base64, ≥256 bits) |
| `JWT_QR_SECRET` | ✅ | *(clé de dev)* | Clé **distincte** pour les tokens QR |
| `JWT_EXPIRATION` | — | `900000` (15 min) | Durée de l'access token (ms) |
| `JWT_REFRESH_EXPIRATION` | — | `2592000000` (30 j) | Durée du refresh token (ms) |
| `ADMIN_EMAIL` | — | `admin@esatic.ci` | Email de l'admin initial |
| `ADMIN_PASSWORD` | recommandé | *(généré + loggé si vide)* | Mot de passe de l'admin initial |
| `APP_SECURITY_REQUIRE_HTTPS` | ✅ (prod) | `false` | Exiger HTTPS |
| `TRUST_FORWARDED_FOR` | ✅ (derrière proxy) | `false` | Faire confiance à `X-Forwarded-*` |
| `LOGIN_RATE_LIMIT_ENABLED` | — | `true` | Anti-bruteforce login/refresh |
| `LOGIN_RATE_LIMIT_MAX` | — | `5` | Tentatives login/min/IP |
| `REFRESH_RATE_LIMIT_MAX` | — | `30` | Tentatives refresh/min/IP |
| `CORS_ALLOWED_ORIGINS` | — | `http://localhost:8080,...` | Origines autorisées |
| `MAIL_ENABLED` | — | `false` | Activer l'envoi réel d'e-mails |
| `MAIL_HOST` / `MAIL_PORT` | si mail | vide / `587` | SMTP (voir §10) |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | si mail | vide | Identifiants SMTP |
| `MAIL_FROM` | si mail | `no-reply@esatic.ci` | Adresse expéditeur |
| `RAPPORTS_DOSSIER` | — | `rapports` | Dossier des PDF générés |

## 5. Profils Spring
- **`dev`** (par défaut) — valeurs locales, secrets de dev fournis, `open-in-view=true`, `ddl-auto=validate`.
- **`prod`** — `SPRING_PROFILES_ACTIVE=prod`. Secrets obligatoires, `require-https=true`, logs sobres, `open-in-view=true` (nécessaire au rendu des vues admin). Voir `application-prod.yml`.
- **`test`** — base **H2** en mémoire, Flyway désactivé, rate-limit désactivé. Voir `src/test/resources/application-test.yml`.

## 6. Base de données & migrations (Flyway)
Le schéma est géré **exclusivement par Flyway** (`src/main/resources/db/migration`) ; Hibernate est en `ddl-auto=validate` (il ne modifie jamais le schéma).

| Migration | Contenu |
|---|---|
| `V1__baseline.sql` | Schéma initial complet |
| `V2__emargement_hors_ligne.sql` | Colonne `hors_ligne` (émargement sans réseau) |
| `V3__refresh_tokens.sql` | Table `refresh_tokens` |

Les migrations s'appliquent **automatiquement au démarrage**. Sur une base vierge, V1→V3 créent tout.

## 7. Sécurité & authentification
- **2 chaînes de filtres** : `/admin/**` (form login + sessions + CSRF) et `/api/**` (JWT, stateless).
- **Access token** JWT court (15 min) + **refresh token** longue durée (30 j) :
  - stocké **haché (SHA-256)**, jamais en clair ; **révocable** ; **rotation** à chaque usage (anti-rejeu).
- **Endpoints d'auth** (`/api/auth/**`, publics) :
  - `POST /login` — connexion → `{token, refreshToken, …}`
  - `POST /register` — **auto-inscription** d'un enseignant par **matricule** (le matricule doit exister dans l'annuaire et ne pas avoir de compte → compte créé *en attente*)
  - `POST /refresh` — échange le refresh token contre un nouvel access token (+ rotation)
  - `POST /logout` — révoque le refresh token
- **Gating de validation** : un enseignant ne peut se connecter que si son statut est `VALIDATED` (validé par l'admin). Le contrôle est rejoué au refresh (un compte rejeté ne peut pas prolonger sa session).
- Requête API non authentifiée → **401** (et non 403), pour piloter le renouvellement côté mobile.
- **Rate-limiting** par IP sur `/login` et `/refresh` (budgets séparés).
- En prod : **HTTPS exigé** (`require-https`), HSTS, respect des en-têtes proxy TLS (`forward-headers-strategy`).

## 8. Émargement & QR code
- **QR universel** (un seul écran, pas par salle) : page **live** `GET /api/qr/display` (publique) — affiche un QR qui **se renouvelle toutes les 30 s**.
- Le QR encode un **JWT court** (`type=QR_UNIVERSAL`, `jti` unique anti-rejeu, `exp` ~30 s) signé avec `JWT_QR_SECRET`. La fraîcheur = preuve de présence. **Ce n'est pas une URL** : inutile d'en faire une image figée.
- **Émargement** : `POST /api/emargements` (QR + signature) ; `POST /api/emargements/hors-ligne` (file d'attente hors-réseau, présence non vérifiée par QR).

## 9. Imports Excel/CSV
Formats : **date `JJ/MM/AAAA`**, **heure `HH:MM`**, CSV **séparateur `;`** (1ʳᵉ ligne = en-tête). Les **référentiels (matière/classe/salle)** et le **matricule** doivent **exister au préalable**.

| Import | Endpoint / accès | Colonnes |
|---|---|---|
| **Annuaire enseignants** | Admin web `/admin/enseignants` (bouton Importer) | `MATRICULE \| NOM \| PRENOM \| DEPARTEMENT \| GRADE` |
| **Planning (admin)** | `POST /api/admin/import/planning` (ROLE_ADMIN) | `DATE \| HEURE_DEBUT \| HEURE_FIN \| MATRICULE \| MATIERE \| CLASSE \| SALLE` |
| **Mon planning (enseignant)** | `POST /api/import/mon-planning` | `DATE \| HEURE_DEBUT \| HEURE_FIN \| MATIERE \| CLASSE \| SALLE` |

## 10. Messagerie e-mail (Resend)
- L'admin peut écrire à **un enseignant ou à tous** : `/admin/messages`.
- Toutes les notifications (validation de compte, rattrapages, relances, messagerie) passent par **Spring Mail (SMTP)** et ne partent que si `MAIL_ENABLED=true`.
- **Fournisseur recommandé : Resend** (domaine vérifié via SPF/DKIM/DMARC) :
  ```
  MAIL_ENABLED=true
  MAIL_HOST=smtp.resend.com
  MAIL_PORT=587            # ⚠️ Render bloque 587 → utiliser 2587 (voir §14)
  MAIL_USERNAME=resend     # littéralement "resend"
  MAIL_PASSWORD=re_xxx     # clé API Resend
  MAIL_FROM=no-reply@<votre-domaine>   # domaine vérifié dans Resend
  ```

## 11. Observabilité (Actuator)
- `GET /actuator/health` (public), `/actuator/info`, `/actuator/metrics` (authentifié).
- L'indicateur de santé **mail est désactivé** (`management.health.mail.enabled=false`) pour ne pas faire échouer la sonde quand aucun SMTP n'est configuré.

## 12. Tests
```bash
./mvnw test
```
Base **H2** en mémoire (profil `test`). Couvre les services et des tests d'intégration (auth, refresh token, émargement…).

## 13. Déploiement en production
Guide complet pas à pas : **[DEPLOIEMENT.md](DEPLOIEMENT.md)**. Deux options :

### Option A — Auto-hébergement Docker (VPS / on-premise)
`docker-compose.prod.yml` = application + PostgreSQL + **Caddy** (reverse proxy + **HTTPS Let's Encrypt automatique**).
```bash
cp .env.prod.example .env.prod        # remplir les secrets
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

### Option B — Gratuit, sans VPS (Render + Neon) — *configuration de production actuelle*
- **Neon** : PostgreSQL managé gratuit → `DB_URL=jdbc:postgresql://<host-neon>/<db>?sslmode=require` (+ `DB_USERNAME`, `DB_PASSWORD`).
- **Render** : Web Service **Docker** (depuis ce dépôt), plan Free, *Health Check Path* `/actuator/health`, variables d'env (§4), domaine personnalisé (HTTPS automatique).
- **UptimeRobot** : ping `/actuator/health` toutes les 5 min pour éviter la mise en veille (cold start) du plan gratuit.
- `render.yaml` (Blueprint) fourni à la racine.

## 14. Dépannage (pièges connus)
| Symptôme | Cause / Solution |
|---|---|
| `/actuator/health` → **503** sans SMTP | Indicateur mail désactivé (`management.health.mail.enabled=false`) |
| **500** à la création d'un enseignant en prod | `open-in-view` doit rester **true** (rendu des vues + cascade `@OneToOne`) ; création `@Transactional` |
| E-mail **`Connection timed out` sur `smtp.resend.com:587`** | **Render bloque les ports SMTP 25/465/587** → utiliser `MAIL_PORT=2587` (port alternatif Resend) ou l'API HTTP Resend |
| Mobile « **serveur inaccessible** » | **Cold start** du plan gratuit Render (réveil > timeout appli) → activer **UptimeRobot** (keep-warm) |
| Connexion impossible après changement de secret | `JWT_SECRET` modifié invalide les tokens existants → garder les secrets **stables** |

---

🤖 Documentation générée avec [Claude Code](https://claude.com/claude-code)
