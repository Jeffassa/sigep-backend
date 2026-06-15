package ci.esatic.sigep.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EmargementRequest {

    @NotNull
    private Long seanceId;

    @NotBlank
    private String qrToken;

    private String signatureBase64; // Optionnelle — le QR token est l'anti-fraude principal
}
