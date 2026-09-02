# Déploiement — SIGEP (backend + mobile)

Guide de mise en production après le durcissement sécurité (C1‑C2, E1‑E5, paiement) et la
migration mobile React Native.

> ⚠️ **Release COUPLÉE.** Le backend rejette désormais un émargement hors‑ligne sans `qrToken`.
> Il faut **distribuer la nouvelle app mobile AVANT (ou en même temps que) le déploiement backend**,
> sinon les anciennes installations ne pourront plus émarger hors‑ligne.

---

## 1. Pré‑requis backend (Render) — À FAIRE AVANT DE DÉPLOYER

### 1.1 Secrets JWT (bloquant au démarrage)
Le garde de démarrage (`SecuriteDemarrageGuard`) **refuse de démarrer** hors profil `dev` si les
clés sont faibles. Chaque clé doit être : **base64**, **≥ 256 bits (32 octets)**, et **`JWT_SECRET` ≠ `JWT_QR_SECRET`**.

```bash
openssl rand -base64 48   # → JWT_SECRET
openssl rand -base64 48   # → JWT_QR_SECRET (différent)
```

- [ ] `JWT_SECRET` défini, base64 ≥ 256 bits
- [ ] `JWT_QR_SECRET` défini, base64 ≥ 256 bits, **différent** de `JWT_SECRET`
- [ ] **Rotation** : les clés qui étaient en clair dans `docker-compose.yml` (historique git) sont
      considérées compromises → en générer de **nouvelles** si elles avaient servi.

### 1.2 Variables d'environnement
- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` (Supabase, session pooler, `sslmode=require`)
- [ ] `app.security.trust-forwarded-for=true` (derrière le proxy Render/nginx)
- [ ] `app.security.trusted-proxy-count=1` (nb de proxys de confiance en amont ; ajuster si CDN)
- [ ] `PORT` (fourni par Render), `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`
- [ ] (optionnel) `MAIL_*`, `AI_ENABLED` + `ANTHROPIC_API_KEY`, `STRIPE_*`, `MOBILE_MONEY_WEBHOOK_SECRET`,
      `QR_DISPLAY_KEY`/`QR_DISPLAY_ALLOWED_IPS`

### 1.3 Base de données
- [ ] Migrations **V18/V19/V20** validées sur Postgres 16 ✅ (testé). Flyway les applique
      automatiquement au démarrage — aucune action manuelle.

---

## 2. Ordre de déploiement (release couplée)

1. [ ] **Mobile d'abord** : builder + distribuer la nouvelle app (voir §3).
2. [ ] **Backend ensuite** : déployer `main` sur Render.
   - Vérifier immédiatement les logs de démarrage : pas d'`IllegalStateException` du garde JWT,
     Flyway « Successfully applied … V20 », application « Started ».

> Si un cutover serré est impossible, envisager une **grace period** (rendre `qrToken` hors‑ligne
> temporairement optionnel côté backend) — au prix de rouvrir brièvement la faille C2.

---

## 3. Mobile (EAS)

Prérequis une fois : compte Expo, `npm i -g eas-cli`, `eas login`, `eas init` (crée
`extra.eas.projectId` dans `app.json`).

```bash
# APK de test interne
eas build --platform android --profile preview
# AAB pour le Play Store
eas build --platform android --profile production
```

- [ ] `preview` (APK) distribué aux testeurs / installé sur les appareils cibles
- [ ] (option store) `production` (AAB) soumis au Play Store
- CI : workflow `.github/workflows/eas-build.yml` (manuel/tag) — nécessite le secret repo `EXPO_TOKEN`.

---

## 4. Vérification post‑déploiement
- [ ] `GET /actuator/health` → `UP`
- [ ] Connexion enseignant (mobile) OK
- [ ] Émargement **en ligne** (scan QR) OK
- [ ] Émargement **hors‑ligne** (réseau coupé → scan → retour réseau → synchro) → séance en
      **attente de validation**, visible par l'admin
- [ ] Admin : valider / refuser un émargement hors‑ligne
- [ ] Paiement (webhook Stripe/MoMo) crédite une seule fois (idempotence)

## 5. Rollback
- Backend : redéployer le commit précédent sur Render. ⚠️ les migrations V18‑V20 sont **additives**
  (colonne `valide`, table `jti_consommes`, contrainte unique, nouveau statut) — un rollback de code
  reste compatible avec le schéma migré (aucun down‑migration requis).
- Mobile : redistribuer la version précédente si nécessaire.

## 6. Restant (non bloquant)
- CI GitHub Actions : débloquer la **facturation du compte** (Actions désactivé compte‑wide).
- Durcissements résiduels : nonce QR↔session (E3), cloisonnement matricule (#5), clé kiosque hors URL,
  stockage mobile (repli cleartext / file hors‑ligne), import Excel en streaming.
