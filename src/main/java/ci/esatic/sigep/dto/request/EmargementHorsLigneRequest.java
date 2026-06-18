package ci.esatic.sigep.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Émargement enregistré hors-ligne puis envoyé au retour du réseau.
 * Pas de token QR : la présence n'est pas vérifiée (marqué horsLigne côté serveur).
 */
@Data
public class EmargementHorsLigneRequest {

    @NotNull
    private Long seanceId;

    @NotBlank
    private String signatureBase64;
}
