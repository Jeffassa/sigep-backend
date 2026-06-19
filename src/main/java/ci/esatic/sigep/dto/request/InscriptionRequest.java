package ci.esatic.sigep.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Auto-inscription d'un enseignant : le matricule doit exister dans l'annuaire importé par l'admin. */
@Data
public class InscriptionRequest {

    @NotBlank
    private String matricule;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8, message = "Le mot de passe doit faire au moins 8 caractères")
    private String password;
}
