package ci.esatic.sigep.dto.response;

import ci.esatic.sigep.entity.StatutDemande;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RattrapageResponse {
    private Long id;
    private String enseignantNom;
    private String enseignantPrenom;
    private String matiereLibelle;
    private String classeLibelle;
    private LocalDate dateSouhaitee;
    private LocalTime heureSouhaitee;
    private String motif;
    private StatutDemande statut;
    private LocalDateTime dateCreation;
    private Long seanceRattrapageId;
}
