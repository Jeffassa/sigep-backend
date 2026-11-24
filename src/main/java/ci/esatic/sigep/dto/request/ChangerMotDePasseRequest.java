package ci.esatic.sigep.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Changement de mot de passe par l'utilisateur lui-même (espace profil de l'app mobile).
 *
 * <p>Les contraintes du nouveau mot de passe sont volontairement IDENTIQUES à celles de
 * l'inscription ({@link RegisterEnseignantRequest}) : sans cela, un compte pourrait se doter
 * après coup d'un mot de passe plus faible que celui exigé à la création.
 */
@Data
public class ChangerMotDePasseRequest {

    @NotBlank(message = "Le mot de passe actuel est obligatoire")
    private String ancienMotDePasse;

    @NotBlank(message = "Le nouveau mot de passe est obligatoire")
    @Size(max = 128, message = "Le mot de passe doit faire au plus 128 caractères") // le reste : PolitiqueMotDePasse
    private String nouveauMotDePasse;
}
