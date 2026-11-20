/*
 * Le code de l'écran de salle, en relief.
 *
 * Chaque case du QR d'illustration devient un petit bloc d'encre posé sur la fiche. À l'arrivée,
 * les blocs se rassemblent depuis le désordre ; toutes les quinze secondes, quand le code se
 * renouvelle, une vague les retourne pour montrer le nouveau motif ; la fiche suit doucement le
 * pointeur. C'est la promesse du produit rendue visible : le code change sans cesse.
 *
 * Le dessin en SVG reste en place et reste la source : il est lu par les lecteurs d'écran, et il
 * suffit seul quand la 3D n'a pas lieu d'être — téléphone (la fiche y est masquée), mouvement
 * réduit demandé, ou navigateur sans WebGL. Three.js n'est téléchargé que si elle a lieu d'être.
 */
const CASES = 25;
const PROFONDEUR = 1.15;
const DUREE_ARRIVEE = 1.5;     // secondes
const DUREE_RETOURNEMENT = 0.7;

const fiche = document.querySelector('.fiche-qr');
const boite = fiche && fiche.querySelector('.qr-box');

function aLieuDEtre() {
    if (!boite || !window.matchMedia) return false;
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false;
    if (getComputedStyle(fiche).display === 'none') return false;   // téléphone : fiche masquée
    try {
        const essai = document.createElement('canvas');
        return !!(essai.getContext('webgl2') || essai.getContext('webgl'));
    } catch (e) {
        return false;
    }
}

function quandLaPageEstLibre(suite) {
    const lancer = () => (window.requestIdleCallback ? requestIdleCallback(suite, { timeout: 1500 }) : setTimeout(suite, 200));
    if (document.readyState === 'complete') lancer();
    else window.addEventListener('load', lancer, { once: true });
}

const facile = (t) => 1 - Math.pow(1 - t, 3);                       // arrivée douce
const balance = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
const borne = (v, a, b) => Math.min(b, Math.max(a, v));

async function demarrer() {
    const THREE = await import('./vendor/three.min.js');

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(30, 1, 1, 200);
    const rendu = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'low-power' });
    rendu.outputColorSpace = THREE.SRGBColorSpace;
    rendu.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    rendu.setClearColor(0x000000, 0);
    rendu.domElement.setAttribute('aria-hidden', 'true');

    scene.add(new THREE.AmbientLight(0xffffff, 1.1));
    const soleil = new THREE.DirectionalLight(0xfff4e0, 1.9);
    soleil.position.set(-14, 22, 30);
    scene.add(soleil);

    const plateau = new THREE.Group();
    scene.add(plateau);

    const total = CASES * CASES;
    const blocs = new THREE.InstancedMesh(
        new THREE.BoxGeometry(0.94, 0.94, PROFONDEUR),
        new THREE.MeshStandardMaterial({ color: new THREE.Color('#081a38'), roughness: 0.55, metalness: 0.08 }),
        total,
    );
    plateau.add(blocs);

    // État de chaque case : allumée maintenant, allumée après le retournement en cours.
    const actuel = new Uint8Array(total);
    const cible = new Uint8Array(total);
    const depart = Array.from({ length: total }, () => {
        const angle = Math.random() * Math.PI * 2;
        const rayon = 18 + Math.random() * 22;
        return {
            x: Math.cos(angle) * rayon,
            y: Math.sin(angle) * rayon,
            z: 10 + Math.random() * 30,
            rx: (Math.random() - 0.5) * 6,
            ry: (Math.random() - 0.5) * 6,
            delai: 0,
        };
    });
    for (let i = 0; i < total; i++) {
        const x = i % CASES, y = Math.floor(i / CASES);
        const distance = Math.hypot(x - 12, y - 12) / 17;
        depart[i].delai = distance * 0.55 + Math.random() * 0.25;
    }

    const lire = (cases) => { if (cases && cases.length === total) cible.set(cases); };
    lire(window.__qrCode);
    actuel.set(cible);

    const instant = () => performance.now() / 1000;
    let debutArrivee = instant();
    let debutRetournement = -1;
    document.addEventListener('sigep:qr', (e) => {
        actuel.set(cible);          // un retournement inachevé se termine d'un coup
        lire(e.detail);
        debutRetournement = instant();
    });

    // Le pointeur incline la fiche, un peu ; sans pointeur, elle se balance seule.
    let visX = 0, visY = 0, inclX = 0, inclY = 0;
    window.addEventListener('pointermove', (e) => {
        if (e.pointerType !== 'mouse') return;
        const r = boite.getBoundingClientRect();
        visY = borne((e.clientX - (r.left + r.width / 2)) / window.innerWidth, -0.5, 0.5) * 0.7;
        visX = borne((e.clientY - (r.top + r.height / 2)) / window.innerHeight, -0.5, 0.5) * 0.5;
    }, { passive: true });

    const pose = new THREE.Object3D();
    function poser(i, t) {
        const x = i % CASES, y = Math.floor(i / CASES);
        const px = x + 0.5 - CASES / 2, py = CASES / 2 - (y + 0.5);
        let echelle = 1, rx = 0, ry = 0, ox = px, oy = py, oz = PROFONDEUR / 2;

        const a = borne((t - debutArrivee - depart[i].delai) / DUREE_ARRIVEE, 0, 1);
        if (a < 1) {
            const k = facile(a), d = depart[i];
            ox = d.x + (px - d.x) * k;
            oy = d.y + (py - d.y) * k;
            oz = d.z + (PROFONDEUR / 2 - d.z) * k;
            rx = d.rx * (1 - k);
            ry = d.ry * (1 - k);
        }

        let allumee = cible[i];
        if (debutRetournement >= 0) {
            // La vague part du coin haut gauche et traverse le code en diagonale.
            const p = borne((t - debutRetournement - (x + y) * 0.022) / DUREE_RETOURNEMENT, 0, 1);
            if (p < 1) {
                const k = balance(p);
                rx += Math.PI * k;
                oz += Math.sin(Math.PI * k) * 1.6;
                const avant = actuel[i], apres = cible[i];
                if (avant && apres) allumee = 1;
                else if (!avant && !apres) allumee = 0;
                else { allumee = 1; echelle = apres ? k : 1 - k; }
            }
        }
        if (!allumee) echelle = 0;
        if (a <= 0) echelle = 0;
        else if (a < 1) echelle *= Math.min(1, a * 3);

        pose.position.set(ox, oy, oz);
        pose.rotation.set(rx, ry, 0);
        pose.scale.setScalar(Math.max(echelle, 0.0001));
        pose.updateMatrix();
        blocs.setMatrixAt(i, pose.matrix);
    }

    function cadrer() {
        const l = boite.clientWidth, h = boite.clientHeight;
        if (!l || !h) return;
        rendu.setSize(l, h, false);
        camera.aspect = l / h;
        // Le code et sa marge blanche doivent tenir dans le plus petit des deux côtés.
        const hauteurVisee = (CASES + 2.6) / Math.min(1, camera.aspect);
        camera.position.set(0, 0, hauteurVisee / (2 * Math.tan((camera.fov * Math.PI) / 360)));
        camera.lookAt(0, 0, 0);
        camera.updateProjectionMatrix();
    }

    function image() {
        const t = instant();
        inclX += (visX - inclX) * 0.06;
        inclY += (visY - inclY) * 0.06;
        plateau.rotation.x = inclX + Math.cos(t * 0.45) * 0.05;
        plateau.rotation.y = inclY + Math.sin(t * 0.55) * 0.08;
        for (let i = 0; i < total; i++) poser(i, t);
        blocs.instanceMatrix.needsUpdate = true;
        if (debutRetournement >= 0 && t - debutRetournement > DUREE_RETOURNEMENT + CASES * 2 * 0.022) {
            actuel.set(cible);
            debutRetournement = -1;
        }
        rendu.render(scene, camera);
    }

    boite.appendChild(rendu.domElement);
    cadrer();
    new ResizeObserver(cadrer).observe(boite);
    image();
    boite.classList.add('en-3d');

    // On ne dessine que ce qui se voit : fiche à l'écran et onglet au premier plan.
    let visible = true;
    const mettreEnMarche = () => rendu.setAnimationLoop(visible && !document.hidden ? image : null);
    new IntersectionObserver((entrees) => { visible = entrees[0].isIntersecting; mettreEnMarche(); }).observe(fiche);
    document.addEventListener('visibilitychange', mettreEnMarche);
    debutArrivee = instant();
    mettreEnMarche();
}

if (aLieuDEtre()) {
    quandLaPageEstLibre(() => {
        demarrer().catch((e) => {
            // Le dessin SVG reste affiché : la page ne perd rien, elle garde simplement son QR plat.
            boite.classList.remove('en-3d');
            if (window.console) console.warn('QR en relief indisponible :', e && e.message);
        });
    });
}
