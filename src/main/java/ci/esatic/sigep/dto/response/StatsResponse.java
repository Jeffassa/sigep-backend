package ci.esatic.sigep.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsResponse {
    // Cumul (toutes les séances de l'enseignant)
    private long seancesTotal;
    private long seancesEmargees;
    private long seancesNonEmargees;
    private double tauxEmargement;      // pourcentage
    private double heuresEffectuees;    // heures émargées
    private long emargementsEnRetard;

    // Cette semaine
    private long seancesSemaine;
    private long emargeesSemaine;
}
