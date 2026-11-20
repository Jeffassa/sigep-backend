/*
 * La mise en scène de la page d'accueil, avec GSAP et ScrollTrigger.
 *
 * La page est un registre : on l'anime comme on remplit une feuille. Les titres s'écrivent mot
 * après mot, le surligneur passe sur « Comptée. », les lignes se posent l'une sous l'autre, les
 * heures se totalisent sous les yeux, la feuille de paie glisse sur le bureau.
 *
 * Trois garde-fous :
 *   - rien n'est masqué par la feuille de style seule : sans ce script, ou s'il échoue, la page
 *     s'affiche entière (l'en-tête ne se cache que si le script s'annonce, voir la page) ;
 *   - le titre principal n'est jamais masqué : c'est le premier contenu que mesurent les moteurs
 *     de recherche, il doit s'afficher dès la première image ;
 *   - mouvement réduit demandé : aucune animation, tout est en place d'emblée.
 */
(function () {
    var racine = document.documentElement;
    if (!window.gsap || !window.ScrollTrigger) {
        racine.classList.remove('mouvement');
        return;
    }
    // Sur une connexion lente, la page a pu se dévoiler seule avant l'arrivée de ce script : on ne
    // remasque pas sous les yeux du visiteur ce qu'il est déjà en train de lire.
    var ouvertureAttendue = racine.classList.contains('mouvement');
    window.__mouvementPret = true;
    gsap.registerPlugin(ScrollTrigger);

    /* ----- Outils ----- */

    // Coupe un titre en mots, sans toucher à sa mise en forme (italique, surligné).
    function mots(el) {
        if (el.dataset.mots) return el.querySelectorAll('.mot');
        (function parcourir(noeud) {
            Array.prototype.slice.call(noeud.childNodes).forEach(function (n) {
                if (n.nodeType === 3) {
                    var frag = document.createDocumentFragment();
                    n.textContent.split(/(\s+)/).forEach(function (m) {
                        if (!m) return;
                        if (/^\s+$/.test(m)) { frag.appendChild(document.createTextNode(m)); return; }
                        var s = document.createElement('span');
                        s.className = 'mot';
                        s.textContent = m;
                        frag.appendChild(s);
                    });
                    n.parentNode.replaceChild(frag, n);
                } else if (n.nodeType === 1 && n.tagName !== 'BR') {
                    parcourir(n);
                }
            });
        })(el);
        el.dataset.mots = '1';
        return el.querySelectorAll('.mot');
    }

    // Fait monter un nombre jusqu'à sa valeur, puis rend le texte exact d'origine.
    function compter(el, options) {
        var origine = el.textContent;
        var decimales = (origine.split(',')[1] || '').replace(/\D/g, '').length;
        var valeur = parseFloat(origine.replace(/[^\d,]/g, '').replace(',', '.'));
        if (!valeur) return;
        var separateur = (origine.match(/\d([^\d,])\d{3}/) || [])[1] || ' ';
        var etat = { v: 0 };
        gsap.to(etat, Object.assign({
            v: valeur,
            duration: 1.4,
            ease: 'power2.out',
            onUpdate: function () {
                var texte = etat.v.toFixed(decimales).split('.');
                texte[0] = texte[0].replace(/\B(?=(\d{3})+(?!\d))/g, separateur);
                el.textContent = texte.join(',');
            },
            onComplete: function () { el.textContent = origine; },
        }, options || {}));
    }

    // Révélation au défilement, une seule fois.
    function aLaVue(cible, depart, options) {
        var elements = gsap.utils.toArray(cible);
        if (!elements.length) return;
        gsap.set(elements, depart);
        ScrollTrigger.batch(elements, Object.assign({
            start: 'top 88%',
            once: true,
            onEnter: function (lot) {
                gsap.to(lot, {
                    opacity: 1, x: 0, y: 0, rotation: 0, rotationX: 0, scale: 1,
                    duration: 0.9, ease: 'power3.out', stagger: 0.09, overwrite: true,
                });
            },
        }, options || {}));
    }

    // Un titre de section s'écrit mot après mot quand il arrive.
    function titreQuiSEcrit(el) {
        var liste = mots(el);
        gsap.set(liste, { opacity: 0, yPercent: 60, rotation: 3 });
        ScrollTrigger.create({
            trigger: el,
            start: 'top 86%',
            once: true,
            onEnter: function () {
                gsap.to(liste, { opacity: 1, yPercent: 0, rotation: 0, duration: 0.8, ease: 'power3.out', stagger: 0.045 });
            },
        });
    }

    var media = gsap.matchMedia();
    media.add('(prefers-reduced-motion: no-preference)', function () {

        /* ----- L'en-tête : la page s'ouvre ----- */
        var ouverture = gsap.timeline({ defaults: { ease: 'power3.out' }, paused: !ouvertureAttendue });
        ouverture
            .fromTo('.nav > a', { opacity: 0, y: -12 }, { opacity: 1, y: 0, duration: 0.6, stagger: 0.05 }, 0)
            .fromTo('.accroche .sur', { backgroundSize: '0% 100%' }, { backgroundSize: '100% 100%', duration: 0.7, ease: 'power2.inOut' }, 0.35)
            .fromTo('.accroche-texte .corps', { opacity: 0, y: 24 }, { opacity: 1, y: 0, duration: 0.8 }, 0.45)
            .fromTo('.appel', { opacity: 0, y: 24 }, { opacity: 1, y: 0, duration: 0.8 }, 0.6)
            .fromTo('.fiche-qr', { opacity: 0, y: -70, rotation: -12 },
                { opacity: 1, y: 0, rotation: 2.2, duration: 1.2, ease: 'back.out(1.5)' }, 0.3);
        if (!ouvertureAttendue) ouverture.progress(1);     // tout est déjà en place
        racine.classList.remove('mouvement');      // la main passe à GSAP

        // En descendant, la fiche du QR remonte un peu plus vite que le texte : un léger relief.
        gsap.to('.fiche-qr', {
            yPercent: -22, ease: 'none',
            scrollTrigger: { trigger: '.accroche', start: 'top top', end: 'bottom top', scrub: true },
        });

        /* ----- Le registre : les lignes se posent ----- */
        aLaVue('.registre .ligne:not(.entete), .registre .total', { opacity: 0, x: -36 }, { start: 'top 85%' });

        /* ----- La journée ----- */
        gsap.utils.toArray('.titre2').forEach(titreQuiSEcrit);
        aLaVue('.journee .large > .corps, .curseur, .bilan', { opacity: 0, y: 30 });
        var arc = document.querySelector('.ciel svg > path');
        if (arc && arc.getTotalLength) {
            var longueur = arc.getTotalLength();
            gsap.set(arc, { strokeDasharray: longueur, strokeDashoffset: longueur });
            gsap.set('.ciel svg > line', { scaleX: 0, transformOrigin: '0% 50%' });
            ScrollTrigger.create({
                trigger: '.ciel', start: 'top 80%', once: true,
                onEnter: function () {
                    gsap.to('.ciel svg > line', { scaleX: 1, duration: 1.2, ease: 'power2.inOut' });
                    gsap.to(arc, { strokeDashoffset: 0, duration: 1.8, ease: 'power2.inOut' });
                    gsap.fromTo('.ciel .heure', { opacity: 0, y: 10 }, { opacity: 1, y: 0, duration: 0.5, stagger: 0.04, delay: 0.4 });
                },
            });
        }
        aLaVue('.seance', { opacity: 0, y: 18 }, { start: 'top 92%' });

        /* ----- Trois gestes : les chiffres tombent, le texte suit ----- */
        gsap.utils.toArray('.geste').forEach(function (geste) {
            var chiffre = geste.querySelector(':scope > .it');
            var texte = geste.querySelector(':scope > div');
            gsap.set(chiffre, { opacity: 0, scale: 0.4, rotation: -24, transformOrigin: '50% 60%' });
            gsap.set(texte, { opacity: 0, x: 40 });
            ScrollTrigger.create({
                trigger: geste, start: 'top 82%', once: true,
                onEnter: function () {
                    gsap.to(chiffre, { opacity: 1, scale: 1, rotation: 0, duration: 0.9, ease: 'back.out(2)' });
                    gsap.to(texte, { opacity: 1, x: 0, duration: 0.9, ease: 'power3.out', delay: 0.12 });
                },
            });
            // Puis le chiffre glisse un peu moins vite que la page.
            gsap.to(chiffre, {
                y: -36, ease: 'none',
                scrollTrigger: { trigger: geste, start: 'top bottom', end: 'bottom top', scrub: true },
            });
        });

        /* ----- La paie : la feuille glisse sur le bureau, les heures se totalisent ----- */
        aLaVue('.paie-texte .corps', { opacity: 0, y: 24 });
        gsap.from('.paie .feuille', {
            y: 140, rotation: -7, opacity: 0, ease: 'power2.out',
            scrollTrigger: { trigger: '.paie', start: 'top 90%', end: 'top 35%', scrub: 0.6 },
        });
        var lignesPaie = gsap.utils.toArray('.paie tbody tr');
        gsap.set(lignesPaie, { opacity: 0, y: 14 });
        ScrollTrigger.create({
            trigger: '.paie .feuille', start: 'top 65%', once: true,
            onEnter: function () {
                gsap.to(lignesPaie, { opacity: 1, y: 0, duration: 0.6, stagger: 0.18, ease: 'power2.out' });
                document.querySelectorAll('.paie .nombre').forEach(function (n, i) { compter(n, { delay: 0.2 + i * 0.18 }); });
                gsap.fromTo('.a-verifier', { opacity: 0, x: 16 }, { opacity: 1, x: 0, duration: 0.6, delay: 0.9 });
            },
        });

        /* ----- Le bordereau : les offres arrivent, les prix se comptent ----- */
        aLaVue('.tarifs .intro', { opacity: 0, y: 24 });
        var offres = gsap.utils.toArray('.offre');
        gsap.set(offres, { opacity: 0, y: 70, rotationX: -14, transformPerspective: 900, transformOrigin: '50% 0%' });
        ScrollTrigger.create({
            trigger: '.bordereau', start: 'top 82%', once: true,
            onEnter: function () {
                gsap.to(offres, { opacity: 1, y: 0, rotationX: 0, duration: 1, stagger: 0.14, ease: 'power3.out' });
                gsap.fromTo('.offre-mise', { scale: 0.94 }, { scale: 1, duration: 1.1, ease: 'elastic.out(1, 0.6)', delay: 0.25 });
                document.querySelectorAll('.offre .prix > span').forEach(function (p, i) { compter(p, { delay: 0.3 + i * 0.14 }); });
            },
        });

        /* ----- Les questions ----- */
        aLaVue('.liste-questions details', { opacity: 0, x: -40 });

        /* ----- La fin ----- */
        // Bas de page : la page ne défile jamais assez pour qu'il atteigne le seuil habituel ;
        // il se révèle dès qu'il entre à l'écran.
        aLaVue('.fin .bouton', { opacity: 0, scale: 0.7 }, {
            start: 'top bottom',
            onEnter: function (lot) { gsap.to(lot, { opacity: 1, scale: 1, duration: 1, ease: 'elastic.out(1, 0.55)' }); },
        });
        aLaVue('.fin .pied', { opacity: 0, y: 24 }, { start: 'top bottom' });
    });

    // Les polices arrivent après le premier calcul : les repères de défilement sont recalculés.
    if (document.fonts && document.fonts.ready) {
        document.fonts.ready.then(function () { ScrollTrigger.refresh(); });
    }
})();
