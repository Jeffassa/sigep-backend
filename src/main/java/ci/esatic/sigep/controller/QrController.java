package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.QrCodeResponse;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.security.ClientIpResolver;
import ci.esatic.sigep.security.SecurityUtils;
import ci.esatic.sigep.service.QrCodeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/qr")
@RequiredArgsConstructor
public class QrController {

    private final QrCodeService qrCodeService;
    private final EtablissementRepository etablissementRepository;
    private final ClientIpResolver clientIpResolver;

    // SECURITE : l'affichage du QR est une PREUVE DE PRESENCE → ne doit jamais être public
    // sur internet. Au moins l'un des deux doit être défini, sinon l'affichage renvoie 403.
    //  - QR_DISPLAY_KEY : clé kiosque que l'écran ajoute en paramètre (?key=...)
    //  - QR_DISPLAY_ALLOWED_IPS : IP(s) publiques autorisées (réseau établissement), séparées par des virgules
    @Value("${app.qr.display.key:}")
    private String displayKey;

    @Value("${app.qr.display.allowed-ips:}")
    private String displayAllowedIps;

    // Endpoint JSON pour le mobile (image Base64 + token) — AUTHENTIFICATION REQUISE
    @GetMapping("/salle/{code}")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<QrCodeResponse>> getQrCode(@PathVariable String code) {
        QrCodeResponse response = qrCodeService.generateQrForSalle(sanitize(code));
        return ResponseEntity.ok(ApiResponse.success("QR Code genere", response));
    }

    // Page HTML pour affichage en salle (kiosque autorisé — clé ou IP, cf. controleAcces)
    @GetMapping(value = "/display/{code}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> displayQrPage(@PathVariable String code,
                                                @RequestParam(required = false) String key,
                                                HttpServletRequest request) {
        String refus = motifRefusAffichage(request, key);
        if (refus != null) return reponseRefus(refus);
        String safeCode = sanitize(code);
        QrCodeResponse qr = qrCodeService.generateQrForSalle(safeCode);
        String html = buildQrPage(safeCode, qr.getQrImageBase64(), qr.getExpiresInSeconds());
        return ResponseEntity.ok(html);
    }

    // Page HTML du QR UNIVERSEL d'émargement, PROPRE À UN ÉTABLISSEMENT (C3 + C4).
    // Accès par clé kiosque PAR TENANT (en base, révocable, aucune variable d'ENV ni redéploiement) :
    //   /api/qr/display?etab=<slug>&key=<kioskKey>
    // Le QR généré porte l'etablissementId : un enseignant ne peut émarger qu'avec le QR de SON établissement.
    @GetMapping(value = "/display", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> displayUniversalQrPage(@RequestParam(required = false) String etab,
                                                         @RequestParam(required = false) String key) {
        Etablissement tenant = tenantKiosqueAutorise(etab, key);
        if (tenant == null) {
            return reponseRefus("Écran non autorisé. Paramètres requis : 'etab' (identifiant de l'établissement) "
                    + "et 'key' (clé kiosque, disponible dans l'espace admin).");
        }
        QrCodeResponse qr = qrCodeService.generateUniversalQr(tenant.getId());
        return ResponseEntity.ok(buildUniversalQrPage(
                tenant.getNomEffectif(), tenant.getCouleurPrincipale(),
                qr.getQrImageBase64(), qr.getExpiresInSeconds()));
    }

    /**
     * Autorise l'affichage du QR d'un établissement via sa clé kiosque (C4).
     * Renvoie l'établissement si le couple (slug, clé) est valide et le tenant actif, sinon null.
     * La clé vit en base (kiosk_key) : régénérable/révocable depuis l'admin, sans redéploiement.
     */
    private Etablissement tenantKiosqueAutorise(String slug, String key) {
        if (slug == null || slug.isBlank() || key == null || key.isBlank()) return null;
        Etablissement e = etablissementRepository.findBySlug(slug.trim()).orElse(null);
        if (e == null || !e.isActif() || e.getKioskKey() == null || e.getKioskKey().isBlank()) return null;
        return SecurityUtils.constantTimeEquals(e.getKioskKey(), key) ? e : null;
    }

    // Seuls alphanumériques, tirets et underscores autorisés — prévient XSS et path traversal
    private String sanitize(String code) {
        if (code == null || !code.matches("[a-zA-Z0-9_\\-]{1,20}")) {
            throw new IllegalArgumentException("Code salle invalide");
        }
        return code;
    }

    /**
     * Contrôle d'accès à l'affichage du QR (preuve de présence). Renvoie le motif de refus,
     * ou null si l'accès est autorisé. Fail-closed : si aucun contrôle n'est configuré,
     * l'affichage est refusé (le QR ne doit jamais être public sur internet).
     */
    private String motifRefusAffichage(HttpServletRequest request, String key) {
        boolean cleConfiguree = displayKey != null && !displayKey.isBlank();
        List<String> ipsAutorisees = Arrays.stream((displayAllowedIps == null ? "" : displayAllowedIps).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        if (!cleConfiguree && ipsAutorisees.isEmpty()) {
            return "Affichage du QR non configuré : définir QR_DISPLAY_KEY ou QR_DISPLAY_ALLOWED_IPS.";
        }
        if (cleConfiguree && key != null && SecurityUtils.constantTimeEquals(displayKey, key)) return null;
        if (!ipsAutorisees.isEmpty() && ipsAutorisees.contains(clientIpResolver.resolve(request))) return null;
        return "Accès à l'affichage du QR refusé.";
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

    private String buildQrPage(String salleCode, String imageBase64, long refreshSeconds) {
        return """
                <!DOCTYPE html>
                <html lang="fr">
                <head>
                    <meta charset="UTF-8"/>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                    <title>SIGEP - Salle %s</title>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body {
                            background: #000666;
                            color: white;
                            font-family: 'Inter', sans-serif;
                            display: flex;
                            flex-direction: column;
                            align-items: center;
                            justify-content: center;
                            height: 100vh;
                            gap: 16px;
                            overflow: hidden;
                        }
                        h1 { font-size: 2rem; font-weight: 900; letter-spacing: -0.02em; }
                        .subtitle { color: #bdc2ff; font-size: 0.95rem; }
                        .qr-container {
                            background: white;
                            padding: 16px;
                            border-radius: 12px;
                            box-shadow: 0 20px 60px rgba(0,0,0,0.4);
                            line-height: 0;
                        }
                        /* QR occupe le maximum de l'écran pour faciliter le scan */
                        img {
                            display: block;
                            width: min(70vh, 70vw);
                            height: min(70vh, 70vw);
                            image-rendering: pixelated; /* pas de flou sur l'upscale */
                        }
                        .timer {
                            font-size: 1.4rem;
                            font-weight: 700;
                            color: #fec330;
                        }
                        .timer.urgent { color: #ff4d4d; animation: pulse 0.5s infinite alternate; }
                        @keyframes pulse { from { opacity: 1; } to { opacity: 0.5; } }
                        .footer { color: #8690ee; font-size: 0.8rem; }
                    </style>
                </head>
                <body>
                    <h1>SIGEP</h1>
                    <p class="subtitle">Salle <strong>%s</strong> — Scannez pour emarger</p>
                    <div class="qr-container">
                        <img src="data:image/png;base64,%s" alt="QR Code" fetchpriority="high"/>
                    </div>
                    <div class="timer" id="timer">Nouveau code dans : <span id="countdown">%d</span>s</div>
                    <p class="footer">Ce QR code se renouvelle automatiquement</p>
                    <script>
                        let seconds = %d;
                        const el = document.getElementById('countdown');
                        const timer = document.getElementById('timer');
                        const interval = setInterval(() => {
                            seconds--;
                            el.textContent = seconds;
                            if (seconds <= 5) timer.classList.add('urgent');
                            if (seconds <= 0) {
                                clearInterval(interval);
                                location.reload();
                            }
                        }, 1000);
                    </script>
                </body>
                </html>
                """.formatted(salleCode, salleCode, imageBase64, refreshSeconds, refreshSeconds);
    }

    private String buildUniversalQrPage(String nomEtablissement, String couleur,
                                        String imageBase64, long refreshSeconds) {
        String bg = (couleur != null && couleur.matches("#[0-9a-fA-F]{3,8}")) ? couleur : "#000666";
        String titre = htmlEscape(nomEtablissement == null || nomEtablissement.isBlank() ? "SIGEP" : nomEtablissement);
        return """
                <!DOCTYPE html>
                <html lang="fr">
                <head>
                    <meta charset="UTF-8"/>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                    <title>%s - Émargement</title>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body {
                            background: %s;
                            color: white;
                            font-family: 'Inter', sans-serif;
                            display: flex;
                            flex-direction: column;
                            align-items: center;
                            justify-content: center;
                            height: 100vh;
                            gap: 16px;
                            overflow: hidden;
                        }
                        h1 { font-size: 2rem; font-weight: 900; letter-spacing: -0.02em; }
                        .subtitle { color: #bdc2ff; font-size: 0.95rem; }
                        .qr-container {
                            background: white;
                            padding: 16px;
                            border-radius: 12px;
                            box-shadow: 0 20px 60px rgba(0,0,0,0.4);
                            line-height: 0;
                        }
                        img {
                            display: block;
                            width: min(70vh, 70vw);
                            height: min(70vh, 70vw);
                            image-rendering: pixelated;
                        }
                        .timer { font-size: 1.4rem; font-weight: 700; color: #fec330; }
                        .timer.urgent { color: #ff4d4d; animation: pulse 0.5s infinite alternate; }
                        @keyframes pulse { from { opacity: 1; } to { opacity: 0.5; } }
                        .footer { color: #8690ee; font-size: 0.8rem; }
                    </style>
                </head>
                <body>
                    <h1>%s</h1>
                    <p class="subtitle">Scannez ce code pour émarger votre séance</p>
                    <div class="qr-container">
                        <img src="data:image/png;base64,%s" alt="QR Code" fetchpriority="high"/>
                    </div>
                    <div class="timer" id="timer">Nouveau code dans : <span id="countdown">%d</span>s</div>
                    <p class="footer">Ce QR code se renouvelle automatiquement</p>
                    <script>
                        let seconds = %d;
                        const el = document.getElementById('countdown');
                        const timer = document.getElementById('timer');
                        const interval = setInterval(() => {
                            seconds--;
                            el.textContent = seconds;
                            if (seconds <= 5) timer.classList.add('urgent');
                            if (seconds <= 0) {
                                clearInterval(interval);
                                location.reload();
                            }
                        }, 1000);
                    </script>
                </body>
                </html>
                """.formatted(titre, bg, titre, imageBase64, refreshSeconds, refreshSeconds);
    }

    /** Échappe le texte injecté dans le HTML de la page kiosque (nom d'établissement). */
    private String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
