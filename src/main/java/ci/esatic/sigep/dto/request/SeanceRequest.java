package ci.esatic.sigep.dto.request;

import ci.esatic.sigep.entity.TypeSeance;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class SeanceRequest {

    @NotNull
    private Long enseignantId;

    @NotNull
    private Long matiereId;

    @NotNull
    private Long classeId;

    @NotNull
    private Long salleId;

    @NotNull
    private LocalDate date;

    @NotNull
    private LocalTime heureDebut;

    @NotNull
    private LocalTime heureFin;

    private TypeSeance type = TypeSeance.NORMALE;
}
