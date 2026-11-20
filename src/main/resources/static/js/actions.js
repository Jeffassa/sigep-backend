/*
 * Actions déclarées dans le HTML par des attributs data-*, à la place des gestionnaires en ligne
 * (onclick, onsubmit…) que la politique de sécurité des scripts interdit.
 *
 *   <form data-confirm="Supprimer ?">        demande confirmation avant l'envoi
 *   <select data-autosubmit>                 envoie le formulaire au changement
 *   <button data-action="theme|palette|deconnexion|annonce-lue|retour">
 *   <div data-action="fermer-palette-fond">  ferme la palette d'un clic sur le fond
 *
 * Écouteurs posés sur le document : ils valent aussi pour le contenu remplacé par la navigation
 * instantanée, sans rien rebrancher.
 */
(function () {
    if (window.__sigepActions) return;
    window.__sigepActions = true;

    // Phase de capture : la confirmation passe AVANT l'interception des formulaires de la
    // navigation instantanée, qui respecte un envoi déjà annulé.
    document.addEventListener('submit', function (e) {
        var form = e.target;
        if (!form || !form.getAttribute) return;
        var message = form.getAttribute('data-confirm');
        if (message && !window.confirm(message)) {
            e.preventDefault();
            e.stopImmediatePropagation();
        }
    }, true);

    document.addEventListener('change', function (e) {
        var champ = e.target;
        if (!champ || !champ.hasAttribute || !champ.hasAttribute('data-autosubmit') || !champ.form) return;
        if (champ.form.requestSubmit) champ.form.requestSubmit(); else champ.form.submit();
    });

    document.addEventListener('click', function (e) {
        var el = e.target && e.target.closest ? e.target.closest('[data-action]') : null;
        if (!el) return;
        switch (el.getAttribute('data-action')) {
            case 'theme':
                if (window.__toggleTheme) window.__toggleTheme();
                break;
            case 'palette':
                if (window.__openPalette) window.__openPalette();
                break;
            case 'deconnexion':
                if (window.__logout) window.__logout();
                break;
            case 'fermer-palette-fond':
                if (e.target === el && window.__closePalette) window.__closePalette();
                break;
            case 'annonce-lue':
                document.cookie = 'sigep_annonce_lue=' + encodeURIComponent(el.getAttribute('data-annonce') || '')
                    + ';path=/admin;max-age=2592000;samesite=lax' + (location.protocol === 'https:' ? ';secure' : '');
                if (el.parentNode) el.parentNode.remove();
                break;
            case 'retour':
                e.preventDefault();
                history.back();
                break;
        }
    });
})();
