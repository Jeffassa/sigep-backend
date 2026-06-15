package ci.esatic.sigep.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatistiquesResponse {
    private long totalEnseignants;
    private long totalSeancesSemaine;
    private long totalEmargesSemaine;
    private long totalRattrapagesEnAttente;
    private double tauxEmargement;
    private long totalHeuresEffectuees;

    // Taux par jour (Lun-Sam) pour le graphe
    private double[] tauxParJour;
}
