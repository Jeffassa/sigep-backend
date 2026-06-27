package ci.esatic.sigep.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Inscription self-service d'un établissement (tenant) + son premier administrateur. */
@Data
public class InscriptionEtablissementRequest {

    @NotBlank
    private String nomEtablissement;

    @NotBlank
    @Email
    private String adminEmail;

    @NotBlank
    @Size(min = 8, message = "Le mot de passe doit faire au moins 8 caractères")
    private String adminPassword;

    private String adminNom;
    private String adminPrenom;
}
