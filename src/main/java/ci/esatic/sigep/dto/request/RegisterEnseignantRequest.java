package ci.esatic.sigep.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
    @Size(min = 10, max = 128, message = "Le mot de passe doit faire entre 10 et 128 caractères") // le reste : PolitiqueMotDePasse
    private String password;

    private String departement;

    private String grade;
}
