package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.QrCodeResponse;
import ci.esatic.sigep.service.QrCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/qr")
@RequiredArgsConstructor
public class QrController {

    private final QrCodeService qrCodeService;

    // Endpoint JSON pour le mobile (image Base64 + token) — AUTHENTIFICATION REQUISE
    @GetMapping("/salle/{code}")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<QrCodeResponse>> getQrCode(@PathVariable String code) {
        QrCodeResponse response = qrCodeService.generateQrForSalle(sanitize(code));
        return ResponseEntity.ok(ApiResponse.success("QR Code genere", response));
    }

    // Page HTML pour affichage en salle (public — tablette fixée en salle)
    @GetMapping(value = "/display/{code}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> displayQrPage(@PathVariable String code) {
        String safeCode = sanitize(code);
        QrCodeResponse qr = qrCodeService.generateQrForSalle(safeCode);
        String html = buildQrPage(safeCode, qr.getQrImageBase64());
        return ResponseEntity.ok(html);
    }

    // Page HTML du QR UNIVERSEL d'émargement (public — écran unique).
    // Se renouvelle automatiquement toutes les 30 s.
    @GetMapping(value = "/display", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> displayUniversalQrPage() {
        QrCodeResponse qr = qrCodeService.generateUniversalQr();
        return ResponseEntity.ok(buildUniversalQrPage(qr.getQrImageBase64()));
    }

    // Seuls alphanumériques, tirets et underscores autorisés — prévient XSS et path traversal
    private String sanitize(String code) {
        if (code == null || !code.matches("[a-zA-Z0-9_\\-]{1,20}")) {
            throw new IllegalArgumentException("Code salle invalide");
        }
        return code;
    }

    private String buildQrPage(String salleCode, String imageBase64) {
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
                    <div class="timer" id="timer">Expiration : <span id="countdown">30</span>s</div>
                    <p class="footer">Ce QR code se renouvelle automatiquement</p>
                    <script>
                        let seconds = 30;
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
                """.formatted(salleCode, salleCode, imageBase64);
    }

    private String buildUniversalQrPage(String imageBase64) {
        return """
                <!DOCTYPE html>
                <html lang="fr">
                <head>
                    <meta charset="UTF-8"/>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                    <title>SIGEP - Émargement</title>
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
                    <h1>SIGEP</h1>
                    <p class="subtitle">Scannez ce code pour émarger votre séance</p>
                    <div class="qr-container">
                        <img src="data:image/png;base64,%s" alt="QR Code" fetchpriority="high"/>
                    </div>
                    <div class="timer" id="timer">Expiration : <span id="countdown">30</span>s</div>
                    <p class="footer">Ce QR code se renouvelle automatiquement</p>
                    <script>
                        let seconds = 30;
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
                """.formatted(imageBase64);
    }
}
