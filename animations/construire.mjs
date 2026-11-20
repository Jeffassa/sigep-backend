// Produit les bibliothèques servies par la page d'accueil, dans src/main/resources/static/js/vendor.
//   - GSAP et ScrollTrigger : recopiés tels que publiés, avec leur mention de licence ;
//   - Three.js : réduit aux pièces listées dans three-entree.js, en un module ES minifié.
// Lancer : npm ci && npm run build (depuis ce dossier).
import { build } from 'esbuild';
import { copyFileSync, mkdirSync, readFileSync, statSync } from 'node:fs';

const SORTIE = '../src/main/resources/static/js/vendor';
mkdirSync(SORTIE, { recursive: true });

for (const f of ['gsap.min.js', 'ScrollTrigger.min.js']) {
    copyFileSync(`node_modules/gsap/dist/${f}`, `${SORTIE}/${f}`);
}

const version = JSON.parse(readFileSync('node_modules/three/package.json', 'utf8')).version;
await build({
    entryPoints: ['three-entree.js'],
    outfile: `${SORTIE}/three.min.js`,
    bundle: true,
    format: 'esm',
    minify: true,
    target: 'es2020',
    legalComments: 'none',
    banner: { js: `/*! three.js ${version} (extrait) | MIT License | Copyright © 2010-2026 three.js authors | https://threejs.org/ */` },
});

for (const f of ['gsap.min.js', 'ScrollTrigger.min.js', 'three.min.js']) {
    console.log(`${f.padEnd(22)} ${Math.round(statSync(`${SORTIE}/${f}`).size / 1024)} Ko`);
}
