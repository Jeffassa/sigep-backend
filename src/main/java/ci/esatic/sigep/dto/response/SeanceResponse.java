package ci.esatic.sigep.dto.response;

import ci.esatic.sigep.entity.StatutSeance;
import ci.esatic.sigep.entity.TypeSeance;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeanceResponse {
    private Long id;
    private LocalDate date;
    private LocalTime heureDebut;
    private LocalTime heureFin;
    private String matiereLibelle;
    private String matiereCode;
    private String classeLibelle;
    private String classeCode;
    private String salleCode;
    private String salleBatiment;
    private String enseignantNom;
    private String enseignantPrenom;
    private TypeSeance type;
    private StatutSeance statut;
}
