package ci.esatic.sigep.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QrCodeResponse {
    private String salleCode;
    private String qrImageBase64;
    private long expiresInSeconds;
    private long generatedAt;
}
