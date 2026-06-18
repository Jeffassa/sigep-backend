package ci.esatic.sigep.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SalleRequest {

    @NotBlank
    private String libelle;

    private String batiment;

    private Integer capacite;
}
