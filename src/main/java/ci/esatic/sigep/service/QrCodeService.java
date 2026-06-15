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

    private static final long QR_EXPIRATION_MS = 30_000L; // 30 secondes
    private static final int QR_SIZE = 400; // Plus grand = lisible de plus loin

    private final JwtService jwtService;

    public QrCodeResponse generateQrForSalle(String salleCode) {
        String token = jwtService.generateQrToken(salleCode, QR_EXPIRATION_MS);
        String qrImageBase64 = generateQrImage(token);

        return QrCodeResponse.builder()
                .salleCode(salleCode)
                .qrImageBase64(qrImageBase64)
                .expiresInSeconds(30)
                .generatedAt(System.currentTimeMillis())
                .build();
    }

    public boolean validateQrToken(String token, String salleCode) {
        return jwtService.isQrTokenValid(token, salleCode);
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
