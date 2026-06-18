package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.response.StatsResponse;
import ci.esatic.sigep.entity.Emargement;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.EmargementRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatsService {

    private final EnseignantRepository enseignantRepository;
    private final SeanceRepository seanceRepository;
    private final EmargementRepository emargementRepository;

    public StatsResponse getStatsEnseignant(Long userId) {
        Enseignant ens = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "userId", userId));
        Long id = ens.getId();

        long total = seanceRepository.countByEnseignantId(id);

        List<Emargement> emargements = emargementRepository.findByEnseignantId(id);
        long emargees = emargements.size();
        double heures = emargements.stream()
                .mapToDouble(e -> Duration.between(
                        e.getSeance().getHeureDebut(), e.getSeance().getHeureFin()).toMinutes() / 60.0)
                .sum();
        long enRetard = emargements.stream().filter(Emargement::isEnRetard).count();
        double taux = total > 0 ? Math.round(emargees * 1000.0 / total) / 10.0 : 0.0;

        LocalDate today = LocalDate.now();
        LocalDate lundi = today.with(DayOfWeek.MONDAY);
        LocalDate dimanche = lundi.plusDays(6);
        long seancesSemaine = seanceRepository.findByEnseignantIdAndDateBetween(id, lundi, dimanche).size();
        long emargeesSemaine = seanceRepository.countEmargeesParEnseignant(id, lundi, dimanche);

        return StatsResponse.builder()
                .seancesTotal(total)
                .seancesEmargees(emargees)
                .seancesNonEmargees(Math.max(0, total - emargees))
                .tauxEmargement(taux)
                .heuresEffectuees(Math.round(heures * 10) / 10.0)
                .emargementsEnRetard(enRetard)
                .seancesSemaine(seancesSemaine)
                .emargeesSemaine(emargeesSemaine)
                .build();
    }
}
