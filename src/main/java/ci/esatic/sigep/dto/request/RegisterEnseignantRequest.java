package ci.esatic.sigep.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterEnseignantRequest {

    @NotBlank
    @Size(max = 64, message = "Le matricule ne doit pas dépasser 64 caractères")
    private String matricule;

    @NotBlank
    @Size(max = 80, message = "Le nom ne doit pas dépasser 80 caractères")
    private String nom;

    @NotBlank
    @Size(max = 80, message = "Le prénom ne doit pas dépasser 80 caractères")
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
