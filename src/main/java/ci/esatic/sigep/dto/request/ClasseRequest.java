package ci.esatic.sigep.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClasseRequest {

    @NotBlank
    private String libelle;

    private String filiere;

    private Integer niveau;
}
