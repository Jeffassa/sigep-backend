package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.response.StatsResponse;
import ci.esatic.sigep.entity.Emargement;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Seance;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.EmargementRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock private EnseignantRepository enseignantRepository;
    @Mock private SeanceRepository seanceRepository;
    @Mock private EmargementRepository emargementRepository;

    @InjectMocks private StatsService statsService;

    private Seance seance2h(LocalTime debut) {
        return Seance.builder().heureDebut(debut).heureFin(debut.plusHours(2)).build();
    }

    @Test
    void getStatsEnseignant_calculeTauxHeuresEtRetards() {
        Long userId = 1L;
        Enseignant ens = Enseignant.builder().id(5L).prenom("Awa").nom("Kone").build();
        when(enseignantRepository.findByUserId(userId)).thenReturn(Optional.of(ens));

        when(seanceRepository.countByEnseignantId(5L)).thenReturn(10L);

        Emargement e1 = Emargement.builder().seance(seance2h(LocalTime.of(8, 0))).enRetard(false).build();
        Emargement e2 = Emargement.builder().seance(seance2h(LocalTime.of(10, 0))).enRetard(true).build();
        when(emargementRepository.findByEnseignantId(5L)).thenReturn(List.of(e1, e2));

        when(seanceRepository.findByEnseignantIdAndDateBetween(eq(5L), any(), any()))
                .thenReturn(List.of(seance2h(LocalTime.of(8, 0))));
        when(seanceRepository.countEmargeesParEnseignant(eq(5L), any(), any())).thenReturn(1L);

        StatsResponse r = statsService.getStatsEnseignant(userId);

        assertThat(r.getSeancesTotal()).isEqualTo(10);
        assertThat(r.getSeancesEmargees()).isEqualTo(2);
        assertThat(r.getSeancesNonEmargees()).isEqualTo(8);
        assertThat(r.getTauxEmargement()).isEqualTo(20.0);     // 2 / 10
        assertThat(r.getHeuresEffectuees()).isEqualTo(4.0);    // 2h + 2h
        assertThat(r.getEmargementsEnRetard()).isEqualTo(1);
        assertThat(r.getSeancesSemaine()).isEqualTo(1);
        assertThat(r.getEmargeesSemaine()).isEqualTo(1);
    }

    @Test
    void getStatsEnseignant_tauxZeroSansSeance() {
        Enseignant ens = Enseignant.builder().id(5L).prenom("Awa").nom("Kone").build();
        when(enseignantRepository.findByUserId(1L)).thenReturn(Optional.of(ens));
        when(seanceRepository.countByEnseignantId(5L)).thenReturn(0L);
        when(emargementRepository.findByEnseignantId(5L)).thenReturn(List.of());
        when(seanceRepository.findByEnseignantIdAndDateBetween(eq(5L), any(), any())).thenReturn(List.of());
        when(seanceRepository.countEmargeesParEnseignant(eq(5L), any(), any())).thenReturn(0L);

        StatsResponse r = statsService.getStatsEnseignant(1L);

        assertThat(r.getSeancesTotal()).isZero();
        assertThat(r.getTauxEmargement()).isZero();
        assertThat(r.getHeuresEffectuees()).isZero();
    }

    @Test
    void getStatsEnseignant_introuvable() {
        when(enseignantRepository.findByUserId(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> statsService.getStatsEnseignant(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
