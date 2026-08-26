package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.response.QrCodeResponse;
import ci.esatic.sigep.security.JwtService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class QrCodeService {

    // Cadence/expiration/taille du QR — externalisées (M2). Défauts = comportement actuel.
    // Validité assez longue pour « scanner → signer → valider », tout en restant une preuve
    // de présence (l'anti-rejeu par jti empêche la réutilisation).
    @org.springframework.beans.factory.annotation.Value("${app.qr.expiration-ms:120000}")
    private long qrExpirationMs;

    @org.springframework.beans.factory.annotation.Value("${app.qr.refresh-seconds:15}")
    private int qrRefreshSeconds;

    @org.springframework.beans.factory.annotation.Value("${app.qr.size:400}")
    private int qrSize;

    private final JwtService jwtService;

    public QrCodeResponse generateQrForSalle(String salleCode) {
        String token = jwtService.generateQrToken(salleCode, qrExpirationMs);
        String qrImageBase64 = generateQrImage(token);

        return QrCodeResponse.builder()
                .salleCode(salleCode)
                .qrImageBase64(qrImageBase64)
                .expiresInSeconds(qrRefreshSeconds)
                .generatedAt(System.currentTimeMillis())
                .build();
    }

    public boolean validateQrToken(String token, String salleCode) {
        return jwtService.isQrTokenValid(token, salleCode);
    }

    /** QR universel d'émargement d'un établissement (un écran par tenant, renouvelé régulièrement). */
    public QrCodeResponse generateUniversalQr(Long etablissementId) {
        String token = jwtService.generateUniversalQrToken(qrExpirationMs, etablissementId);
        return QrCodeResponse.builder()
                .salleCode(null)
                .qrImageBase64(generateQrImage(token))
                .expiresInSeconds(qrRefreshSeconds)
                .generatedAt(System.currentTimeMillis())
                .build();
    }

    /** Lecture unique du QR universel (validité + établissement + jti) — un seul parse/vérif. */
    public JwtService.QrUniversel lireQrUniversel(String token) {
        return jwtService.lireQrUniversel(token);
    }

    /** Lecture du QR universel pour la synchro hors-ligne : signature vérifiée, expiration tolérée. */
    public JwtService.QrUniverselDiffere lireQrUniverselDiffere(String token) {
        return jwtService.lireQrUniverselDiffere(token);
    }

    private String generateQrImage(String content) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            // L (7% redondance) suffisent pour un écran tablette : contraste parfait,
            // pas de dégâts physiques → QR 2x moins dense = scan instantané
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8"); // évite la détection de charset
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, qrSize, qrSize, hints);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);

            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (WriterException | IOException e) {
            log.error("Erreur generation QR Code : {}", e.getMessage());
            throw new RuntimeException("Impossible de generer le QR Code", e);
        }
    }
}
