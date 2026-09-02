# Tailwind (CSS statique)

En production on n'utilise **pas** le Play-CDN `cdn.tailwindcss.com` : il recompile tout
le CSS dans le navigateur à **chaque chargement de page** (flash / FOUC + lenteur, et c'est
explicitement déconseillé en prod). À la place, on génère un **CSS statique** minifié servi
depuis `src/main/resources/static/css/tailwind.css` et référencé dans `admin/fragments.html`.

## Régénérer le CSS

Nécessaire **après tout ajout d'une classe Tailwind** dans un template (sinon la classe
sera absente du CSS statique).

```bash
cd tailwind
npm install      # une fois
npm run build    # régénère ../src/main/resources/static/css/tailwind.css
# ou en continu pendant le dev :
npm run watch
```

Le scan des classes couvre `../src/main/resources/templates/**/*.html`. Le thème (couleurs
`ink`/`muted`/`line`/`paper` mappées sur les variables CSS de `sigep.css`, polices, plugin
`forms`) est défini dans `tailwind.config.js` — il reproduit l'ancienne config inline du CDN.

> TODO possible : intégrer `npm run build` à l'étape de build Docker pour l'automatiser.
