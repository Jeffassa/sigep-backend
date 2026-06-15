package ci.esatic.sigep.dto.response;

import ci.esatic.sigep.entity.StatutEnseignant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnseignantResponse {
    private Long id;
    private String matricule;
    private String nom;
    private String prenom;
    private String email;
    private String departement;
    private String grade;
    private StatutEnseignant statut;
    private String photoUrl;
}
