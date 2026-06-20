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

    // Validité du token QR : assez longue pour « scanner → signer → valider »,
    // tout en restant une preuve de présence (l'anti-rejeu par jti empêche la réutilisation).
    private static final long QR_EXPIRATION_MS = 120_000L; // 2 minutes
    private static final int QR_REFRESH_SECONDS = 60;      // cadence de rafraîchissement de l'affichage
    private static final int QR_SIZE = 400; // Plus grand = lisible de plus loin

    private final JwtService jwtService;

    public QrCodeResponse generateQrForSalle(String salleCode) {
        String token = jwtService.generateQrToken(salleCode, QR_EXPIRATION_MS);
        String qrImageBase64 = generateQrImage(token);

        return QrCodeResponse.builder()
                .salleCode(salleCode)
                .qrImageBase64(qrImageBase64)
                .expiresInSeconds(QR_REFRESH_SECONDS)
                .generatedAt(System.currentTimeMillis())
                .build();
    }

    public boolean validateQrToken(String token, String salleCode) {
        return jwtService.isQrTokenValid(token, salleCode);
    }

    /** QR universel d'émargement (un seul écran, renouvelé toutes les 30 s). */
    public QrCodeResponse generateUniversalQr() {
        String token = jwtService.generateUniversalQrToken(QR_EXPIRATION_MS);
        return QrCodeResponse.builder()
                .salleCode(null)
                .qrImageBase64(generateQrImage(token))
                .expiresInSeconds(QR_REFRESH_SECONDS)
                .generatedAt(System.currentTimeMillis())
                .build();
    }

    public boolean validateUniversalToken(String token) {
        return jwtService.isUniversalQrTokenValid(token);
    }

    /** Identifiant unique (jti) du token QR, pour l'anti-rejeu. */
    public String extractTokenId(String token) {
        return jwtService.extractQrJti(token);
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
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);

            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (WriterException | IOException e) {
            log.error("Erreur generation QR Code : {}", e.getMessage());
            throw new RuntimeException("Impossible de generer le QR Code", e);
        }
    }
}
