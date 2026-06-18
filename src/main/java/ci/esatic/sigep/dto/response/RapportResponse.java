package ci.esatic.sigep.dto.response;

import ci.esatic.sigep.entity.TypeRapport;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RapportResponse {
    private Long id;
    private String enseignantNom;
    private String enseignantPrenom;
    private LocalDate periodeDebut;
    private LocalDate periodeFin;
    private String nomFichier;
    private Long tailleFichierOctets;
    private LocalDateTime dateGeneration;
    private TypeRapport type;
    // Indicateur intelligent : assiduité de l'enseignant sur la période du rapport
    private double tauxEmargement;   // % de séances émargées
    private String niveau;           // Excellent / Bon / Moyen / Faible / —
}
