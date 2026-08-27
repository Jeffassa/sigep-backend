package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Classe;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Matiere;
import ci.esatic.sigep.entity.Salle;
import ci.esatic.sigep.entity.Seance;
import ci.esatic.sigep.entity.StatutSeance;
import ci.esatic.sigep.entity.TypeSeance;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.ClasseRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.MatiereRepository;
import ci.esatic.sigep.repository.SalleRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Anti-IDOR sur GET /api/seances/{id} : un enseignant ne peut consulter que SES séances.
 * La séance d'un collègue (même établissement) doit renvoyer 404 (ResourceNotFound),
 * sans révéler son existence.
 */
class SeanceServiceIdorTest {

    private SeanceRepository seanceRepository;
    private SeanceService service;

    @BeforeEach
    void setUp() {
        seanceRepository = Mockito.mock(SeanceRepository.class);
        service = new SeanceService(seanceRepository,
                Mockito.mock(EnseignantRepository.class),
                Mockito.mock(MatiereRepository.class),
                Mockito.mock(ClasseRepository.class),
                Mockito.mock(SalleRepository.class),
                new ci.esatic.sigep.mapper.SeanceMapperImpl());   // mapper réel généré
    }

    private Seance seanceDe(long enseignantId) {
        Enseignant ens = Enseignant.builder().matricule("M").nom("Nom").prenom("Prenom").build();
        ens.setId(enseignantId);
        return Seance.builder()
                .date(LocalDate.now())
                .heureDebut(LocalTime.of(8, 0)).heureFin(LocalTime.of(10, 0))
                .enseignant(ens)
                .matiere(Matiere.builder().libelle("Algorithmique").build())
                .classe(Classe.builder().libelle("L3").build())
                .salle(Salle.builder().libelle("B12").batiment("B").build())
                .type(TypeSeance.NORMALE).statut(StatutSeance.A_FAIRE)
                .build();
    }

    @Test
    void enseignant_accedeASaPropreSeance() {
        when(seanceRepository.findById(1L)).thenReturn(Optional.of(seanceDe(7L)));
        assertThat(service.getByIdPourEnseignant(1L, 7L)).isNotNull();
    }

    @Test
    void enseignant_neVoitPasLaSeanceDunCollegue() {
        when(seanceRepository.findById(1L)).thenReturn(Optional.of(seanceDe(7L)));
        assertThatThrownBy(() -> service.getByIdPourEnseignant(1L, 9L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void seanceInexistante_renvoie404() {
        when(seanceRepository.findById(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getByIdPourEnseignant(42L, 7L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
