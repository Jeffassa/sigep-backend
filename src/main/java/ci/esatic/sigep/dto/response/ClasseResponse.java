package ci.esatic.sigep.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClasseResponse {
    private Long id;
    private String libelle;
    private String filiere;
    private Integer niveau;
}
