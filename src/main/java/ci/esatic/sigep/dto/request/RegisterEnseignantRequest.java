package ci.esatic.sigep.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterEnseignantRequest {

    @NotBlank
    private String matricule;

    @NotBlank
    private String nom;

    @NotBlank
    private String prenom;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8, message = "Le mot de passe doit faire au moins 8 caractères")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
             message = "Le mot de passe doit contenir au moins une lettre et un chiffre")
    private String password;

    private String departement;

    private String grade;
}
