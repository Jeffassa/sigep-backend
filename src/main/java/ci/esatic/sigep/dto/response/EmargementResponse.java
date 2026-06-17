package ci.esatic.sigep.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmargementResponse {
    private Long id;
    private Long seanceId;
    private String matiereLibelle;
    private String classeLibelle;
    private String salleLibelle;
    private LocalDateTime dateHeure;
    private boolean enRetard;
    private String enseignantNom;
    private String enseignantPrenom;
}
