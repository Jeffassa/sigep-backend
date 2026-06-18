package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.response.RapportResponse;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.RapportPdf;
import ci.esatic.sigep.entity.TypeRapport;
import ci.esatic.sigep.repository.EmargementRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.RapportPdfRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RapportServiceTest {

    @Mock private EnseignantRepository enseignantRepository;
    @Mock private EmargementRepository emargementRepository;
    @Mock private RapportPdfRepository rapportPdfRepository;
    @Mock private SeanceRepository seanceRepository;

    @InjectMocks private RapportService rapportService;

    private static final LocalDate DEBUT = LocalDate.of(2026, 6, 8);
    private static final LocalDate FIN = LocalDate.of(2026, 6, 14);

    private RapportPdf rapport() {
        Enseignant ens = Enseignant.builder().id(5L).nom("Kone").prenom("Awa").build();
        return RapportPdf.builder()
                .id(1L).enseignant(ens)
                .periodeDebut(DEBUT).periodeFin(FIN)
                .nomFichier("r.pdf").tailleFichierOctets(10L)
                .type(TypeRapport.HEBDOMADAIRE).dateGeneration(LocalDateTime.now())
                .build();
    }

    private RapportResponse niveauPour(long total, long emargees) {
        when(rapportPdfRepository.findFiltres(null, null, null)).thenReturn(List.of(rapport()));
        when(seanceRepository.countByEnseignantIdAndPeriode(5L, DEBUT, FIN)).thenReturn(total);
        when(seanceRepository.countEmargeesParEnseignant(5L, DEBUT, FIN)).thenReturn(emargees);
        return rapportService.getRapportsFiltres(null, null, null).get(0);
    }

    @Test
    void niveau_excellent_quand_taux_90() {
        RapportResponse r = niveauPour(10, 9);
        assertThat(r.getTauxEmargement()).isEqualTo(90.0);
        assertThat(r.getNiveau()).isEqualTo("Excellent");
    }

    @Test
    void niveau_bon_quand_taux_80() {
        assertThat(niveauPour(10, 8).getNiveau()).isEqualTo("Bon");
    }

    @Test
    void niveau_moyen_quand_taux_60() {
        assertThat(niveauPour(10, 6).getNiveau()).isEqualTo("Moyen");
    }

    @Test
    void niveau_faible_quand_taux_30() {
        assertThat(niveauPour(10, 3).getNiveau()).isEqualTo("Faible");
    }

    @Test
    void niveau_indetermine_sans_seance() {
        RapportResponse r = niveauPour(0, 0);
        assertThat(r.getTauxEmargement()).isZero();
        assertThat(r.getNiveau()).isEqualTo("—"); // tiret long
    }
}
