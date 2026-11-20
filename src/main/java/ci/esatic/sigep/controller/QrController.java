package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.response.QrCodeResponse;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.security.ClientIpResolver;
import ci.esatic.sigep.service.JetonEcranSalle;
import ci.esatic.sigep.service.QrCodeService;
import ci.esatic.sigep.service.ReseauEcran;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/qr")
@RequiredArgsConstructor
public class QrController {

    private final QrCodeService qrCodeService;
    private final EtablissementRepository etablissementRepository;
    private final ClientIpResolver clientIpResolver;
    private final ci.esatic.sigep.service.AbonnementService abonnementService;
    private final JetonEcranSalle jetonEcranSalle;

    /**
     * Page HTML du QR universel d'émargement, PROPRE À UN ÉTABLISSEMENT : {@code /api/qr/display?etab=<slug>}.
     *
     * <p>L'appareil est autorisé par un cookie signé, posé quand l'administration ouvre l'écran
     * ({@code /admin/ecran-emargement/ouvrir}) : aucune clé ne passe par l'adresse. Si
     * l'établissement a déclaré le réseau de ses salles, l'écran ne s'affiche que depuis ce réseau :
     * un cookie emporté chez soi ne suffit plus à afficher le code ailleurs.
     * Le QR porte l'etablissementId : un enseignant n'émarge qu'avec celui de SON établissement.
     */
    @GetMapping(value = "/display", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> displayUniversalQrPage(@RequestParam(required = false) String etab,
                                                         @CookieValue(name = JetonEcranSalle.COOKIE, required = false) String jeton,
                                                         HttpServletRequest request) {
        Etablissement tenant = tenantActif(etab);
        if (tenant == null || !jetonEcranSalle.valide(tenant, jeton)) return refusEcran();
        if (!ReseauEcran.autorise(tenant.getEcranReseaux(), clientIpResolver.resolve(request))) {
            return reponseRefus("Cet écran n'est pas sur le réseau de l'établissement. "
                    + "Affichez-le depuis un ordinateur de l'établissement.");
        }
        QrCodeResponse qr = qrCodeService.generateUniversalQr(tenant.getId());
        String nonce = (String) request.getAttribute(ci.esatic.sigep.security.CspNonceFilter.ATTRIBUT);
        return ResponseEntity.ok(buildUniversalQrPage(tenant.getNomEffectif(), tenant.getCouleurPrincipale(),
                qr.getQrImageBase64(), qr.getExpiresInSeconds(), nonce == null ? "" : nonce));
    }

    private ResponseEntity<String> refusEcran() {
        return reponseRefus("Cet écran n'est pas autorisé. Ouvrez-le depuis l'espace d'administration, "
                + "page Écran d'émargement.");
    }

    /**
     * L'établissement désigné, s'il existe, a une clé d'écran et n'est pas suspendu ; sinon null.
     * La clé vit en base (kiosk_key) : régénérable depuis l'admin, sans redéploiement.
     */
    private Etablissement tenantActif(String slug) {
        if (slug == null || slug.isBlank()) return null;
        Etablissement e = etablissementRepository.findBySlug(slug.trim()).orElse(null);
        if (e == null || abonnementService.accesSuspendu(e)
                || e.getKioskKey() == null || e.getKioskKey().isBlank()) return null;
        return e;
    }

    private ResponseEntity<String> reponseRefus(String motif) {
        String html = "<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"UTF-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>"
                + "<title>SIGEP</title></head><body style=\"font-family:sans-serif;background:#000666;"
                + "color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;"
                + "margin:0;text-align:center;padding:24px\"><div><h1 style=\"margin:0 0 8px\">Accès refusé</h1>"
                + "<p style=\"color:#bdc2ff\">" + motif + "</p></div></body></html>";
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_HTML).body(html);
    }

    private String buildUniversalQrPage(String nomEtablissement, String couleur,
                                        String imageBase64, long refreshSeconds, String nonce) {
        // Fond clair exclu : le texte de l'écran est clair, et la couleur vient d'un réglage client.
        String bg = (couleur != null && couleur.matches("#[0-9a-fA-F]{6}") && estSombre(couleur)) ? couleur : "#081a38";
        String titre = htmlEscape(nomEtablissement == null || nomEtablissement.isBlank() ? "SIGEP" : nomEtablissement);
        return """
                <!DOCTYPE html>
                <html lang="fr">
                <head>
                    <meta charset="UTF-8"/>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                    <title>%s - Émargement</title>
                    <link rel="preconnect" href="https://fonts.googleapis.com"/>
                    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin="crossorigin"/>
                    <link href="https://fonts.googleapis.com/css2?family=Archivo:wdth,wght@62..125,400..900&amp;family=Bodoni+Moda:ital,opsz,wght@1,6..96,400..700&amp;family=DM+Mono:wght@400;500&amp;display=swap" rel="stylesheet"/>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        html, body { height: 100%%; }
                        body {
                            background: %s;
                            color: #f6ecd9;
                            font-family: Archivo, Arial, sans-serif;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            gap: clamp(24px, 5vw, 72px);
                            padding: 4vh 5vw;
                            overflow: hidden;
                        }
                        /* Le blanc franc autour du code : un lecteur de QR a besoin de cette marge. */
                        .code { flex: none; background: #fff; padding: 2.5vmin; line-height: 0; }
                        img { display: block; width: min(78vh, 46vw); height: min(78vh, 46vw); image-rendering: pixelated; }
                        .texte { min-width: 0; max-width: 620px; }
                        .etablissement { font-stretch: 75%%; font-weight: 800; font-size: clamp(20px, 3.4vh, 34px); line-height: 1.2; }
                        h1 { margin-top: 2vh; font-stretch: 75%%; font-weight: 900; font-size: clamp(56px, 14vh, 136px); line-height: .92; letter-spacing: -.015em; color: #fff; }
                        h1 em { font-family: 'Bodoni Moda', Didot, Georgia, serif; font-style: italic; font-weight: 500; font-stretch: 100%%; color: #ffbe57; }
                        .consigne { margin-top: 3vh; font-size: clamp(18px, 3.2vh, 30px); line-height: 1.35; }
                        .jauge { margin-top: 4vh; height: 8px; background: rgba(255,255,255,.18); }
                        .jauge div { height: 8px; width: 100%%; background: #f5a623; transition: width 1s linear; }
                        .delai { margin-top: 1.5vh; font-family: 'DM Mono', ui-monospace, Consolas, monospace; font-size: clamp(16px, 2.8vh, 26px); font-variant-numeric: tabular-nums; }
                        @media (max-aspect-ratio: 1/1) {
                            body { flex-direction: column; text-align: center; }
                            img { width: min(82vw, 58vh); height: min(82vw, 58vh); }
                            h1 br { display: none; }
                        }
                        @media (prefers-reduced-motion: reduce) { .jauge div { transition: none; } }
                    </style>
                </head>
                <body>
                    <div class="code">
                        <img src="data:image/png;base64,%s" alt="QR code d'émargement" fetchpriority="high"/>
                    </div>
                    <div class="texte">
                        <p class="etablissement">%s</p>
                        <h1>Scannez,<br/> <em>signez.</em></h1>
                        <p class="consigne">Ouvrez l'application SIGEP et visez ce code pour émarger votre séance.</p>
                        <div class="jauge"><div id="jauge"></div></div>
                        <p class="delai">Nouveau code dans <span id="countdown">%d</span> s</p>
                    </div>
                    <script nonce="%s">
                        const duree = %d;
                        let seconds = duree;
                        const el = document.getElementById('countdown');
                        const jauge = document.getElementById('jauge');
                        const interval = setInterval(() => {
                            seconds--;
                            el.textContent = seconds;
                            jauge.style.width = (seconds / duree * 100) + '%%';
                            if (seconds <= 0) {
                                clearInterval(interval);
                                location.reload();
                            }
                        }, 1000);
                    </script>
                </body>
                </html>
                """.formatted(titre, bg, imageBase64, titre, refreshSeconds, nonce, refreshSeconds);
    }

    /** Luminance perçue d'une couleur #rrggbb déjà validée : assez sombre pour porter du texte clair. */
    private static boolean estSombre(String couleur) {
        int r = Integer.parseInt(couleur.substring(1, 3), 16);
        int g = Integer.parseInt(couleur.substring(3, 5), 16);
        int b = Integer.parseInt(couleur.substring(5, 7), 16);
        return 0.299 * r + 0.587 * g + 0.114 * b < 110;
    }

    /** Échappe le texte injecté dans le HTML de la page kiosque (nom d'établissement). */
    private String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
