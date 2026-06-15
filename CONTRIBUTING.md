# Guide de contribution — SIGEP Backend

Ce projet suit le modèle **GitHub Flow** : la branche `main` est toujours
déployable, et tout changement passe par une branche dédiée puis une Pull Request.

## 🌳 Modèle de branches (GitHub Flow)

- `main` : branche de production, **protégée**. On n'y pousse jamais directement.
- Branches de travail : créées à partir de `main`, fusionnées via Pull Request.

### Convention de nommage des branches

| Préfixe      | Usage                                   | Exemple                          |
|--------------|-----------------------------------------|----------------------------------|
| `feature/`   | Nouvelle fonctionnalité                 | `feature/export-rapports-zip`    |
| `fix/`       | Correction de bug                       | `fix/validation-dates-rapport`   |
| `hotfix/`    | Correctif urgent en production          | `hotfix/jwt-expiration`          |
| `refactor/`  | Refactorisation sans changement de comportement | `refactor/service-emargement` |
| `test/`      | Ajout ou modification de tests          | `test/integration-rattrapage`    |
| `docs/`      | Documentation                           | `docs/readme-installation`       |
| `chore/`     | Maintenance, config, dépendances        | `chore/bump-spring-boot`         |

## 🔄 Workflow

```bash
# 1. Partir d'un main à jour
git checkout main
git pull origin main

# 2. Créer une branche de travail
git checkout -b feature/ma-fonctionnalite

# 3. Travailler, puis committer (voir convention ci-dessous)
git add .
git commit -m "feat: ajouter l'export ZIP des rapports"

# 4. Pousser la branche
git push -u origin feature/ma-fonctionnalite

# 5. Ouvrir une Pull Request vers main sur GitHub
# 6. Après revue + CI verte, fusionner (squash recommandé) puis supprimer la branche
```

## ✍️ Convention de commits (Conventional Commits)

Format : `<type>(<portée optionnelle>): <description à l'impératif>`

| Type       | Quand l'utiliser                                    |
|------------|-----------------------------------------------------|
| `feat`     | Nouvelle fonctionnalité                             |
| `fix`      | Correction de bug                                   |
| `docs`     | Documentation uniquement                            |
| `refactor` | Refactorisation (ni bug ni fonctionnalité)          |
| `perf`     | Amélioration de performance                         |
| `test`     | Ajout/modification de tests                         |
| `build`    | Build, dépendances (Maven)                          |
| `ci`       | Intégration continue                                |
| `chore`    | Tâches diverses sans impact sur le code de prod     |

Exemples :
```
feat(emargement): valider le QR code par salle
fix(rapports): empêcher une date de début postérieure à la date de fin
test(auth): ajouter les tests d'intégration de connexion
```

## ✅ Avant d'ouvrir une Pull Request

```bash
./mvnw test          # tous les tests doivent passer
./mvnw clean verify  # build complet
```

- La PR doit cibler `main` et être liée à une issue si elle existe.
- Remplir le template de PR (description, tests effectués, checklist).
- Ne jamais committer de secrets : utiliser les variables d'environnement
  (`JWT_SECRET`, `DB_PASSWORD`, etc.).

## 🚀 Lancer le projet en local

```bash
docker compose up -d   # PostgreSQL
./mvnw spring-boot:run
```
