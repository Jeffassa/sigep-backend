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

    // Position du téléphone au moment d'émarger, envoyée quand l'établissement a défini une zone
    // d'émargement. Elle sert au contrôle puis est oubliée : elle n'est jamais enregistrée.
    @jakarta.validation.constraints.DecimalMin("-90") @jakarta.validation.constraints.DecimalMax("90")
    private Double latitude;

    @jakarta.validation.constraints.DecimalMin("-180") @jakarta.validation.constraints.DecimalMax("180")
    private Double longitude;

    @jakarta.validation.constraints.PositiveOrZero
    private Double precisionMetres;

    // Android signale une position fournie par une application de simulation.
    private Boolean positionSimulee;

    public ci.esatic.sigep.service.ZoneEmargement.Position position() {
        return new ci.esatic.sigep.service.ZoneEmargement.Position(latitude, longitude, precisionMetres, positionSimulee);
    }
}
