package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.StatistiquesResponse;
import ci.esatic.sigep.entity.StatutDemande;
import ci.esatic.sigep.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/statistiques")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class StatistiquesController {

    private final EnseignantRepository enseignantRepository;
    private final SeanceRepository seanceRepository;
    private final DemandeRattrapageRepository demandeRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<StatistiquesResponse>> getStatistiques() {
        LocalDate lundi = LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDate samedi = lundi.plusDays(5);

        long totalEnseignants = enseignantRepository.count();
        long totalSeancesSemaine = seanceRepository.countAllByDateBetween(lundi, samedi);
        long totalEmargesSemaine = seanceRepository.countAllEmargesByDateBetween(lundi, samedi);
        long rattrapagesEnAttente = demandeRepository.countByStatut(StatutDemande.EN_ATTENTE);

        // Total heures (2h par séance émargée en moyenne)
        long totalHeures = totalEmargesSemaine * 2;

        double tauxEmargement = totalSeancesSemaine > 0
                ? (double) totalEmargesSemaine / totalSeancesSemaine * 100
                : 0;

        // Taux par jour (Lun-Sam) pour le graphe
        double[] tauxParJour = new double[6];
        for (int i = 0; i < 6; i++) {
            LocalDate jour = lundi.plusDays(i);
            long prevues = seanceRepository.countAllByDate(jour);
            long emargees = seanceRepository.countAllEmargesByDate(jour);
            tauxParJour[i] = prevues > 0 ? (double) emargees / prevues * 100 : 0;
        }

        StatistiquesResponse stats = StatistiquesResponse.builder()
                .totalEnseignants(totalEnseignants)
                .totalSeancesSemaine(totalSeancesSemaine)
                .totalEmargesSemaine(totalEmargesSemaine)
                .totalRattrapagesEnAttente(rattrapagesEnAttente)
                .tauxEmargement(tauxEmargement)
                .totalHeuresEffectuees(totalHeures)
                .tauxParJour(tauxParJour)
                .build();

        return ResponseEntity.ok(ApiResponse.success("Statistiques", stats));
    }
}
