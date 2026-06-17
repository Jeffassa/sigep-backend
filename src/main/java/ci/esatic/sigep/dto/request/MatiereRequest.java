package ci.esatic.sigep.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MatiereRequest {

    @NotBlank
    private String libelle;

    private String description;
}
