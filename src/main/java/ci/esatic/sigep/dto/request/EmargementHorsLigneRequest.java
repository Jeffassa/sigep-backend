package ci.esatic.sigep.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Émargement enregistré hors-ligne puis envoyé au retour du réseau.
 * Le QR scanné pendant la séance est OBLIGATOIRE : c'est la preuve de présence (vérifiée à la
 * synchro — signature + établissement + fenêtre horaire via l'iat signé + anti-rejeu). La présence
 * reste À CONFIRMER par l'admin (séance EN_ATTENTE_VALIDATION) tant qu'elle n'est pas validée.
 */
@Data
public class EmargementHorsLigneRequest {

    @NotNull
    private Long seanceId;

    // Optionnelle (le QR est la preuve de présence) ; validée seulement pour son format si fournie.
    private String signatureBase64;

    // Token du QR universel scanné hors-ligne pendant la séance (JWT signé par le kiosque). OBLIGATOIRE.
    @NotBlank
    private String qrToken;
}
