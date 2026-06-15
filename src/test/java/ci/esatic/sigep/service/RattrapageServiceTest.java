package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.RattrapageRequest;
import ci.esatic.sigep.dto.response.RattrapageResponse;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RattrapageServiceTest {

    @Mock private DemandeRattrapageRepository demandeRepository;
    @Mock private EnseignantRepository enseignantRepository;
    @Mock private MatiereRepository matiereRepository;
    @Mock private ClasseRepository classeRepository;
    @Mock private SeanceRepository seanceRepository;

    @InjectMocks private RattrapageService rattrapageService;

    private static final Long USER_ID    = 1L;
    private static final Long SEANCE_ID  = 10L;
    private static final Long DEMANDE_ID = 20L;

    private Enseignant enseignant;
    private Matiere matiere;
    private Classe classe;
    private Salle salle;
    private Seance seance;

    @BeforeEach
    void setUp() {
        Role role = new Role(1, ERole.ROLE_ENSEIGNANT);
        User user = User.builder()
                .id(USER_ID).email("prof@esatic.ci").password("encoded").roles(Set.of(role))
                .build();

        matiere = Matiere.builder().id(1L).code("INF301").libelle("Algo avancée").build();
        classe  = Classe.builder().id(1L).code("L3GL").libelle("L3 Génie Logiciel").filiere("GL").build();
        salle   = Salle.builder().id(1L).code("A101").batiment("A").capacite(30).build();

        enseignant = Enseignant.builder()
                .id(5L).matricule("ENS001").nom("Assale").prenom("Jean")
                .statut(StatutEnseignant.VALIDATED).user(user)
                .build();

        seance = Seance.builder()
                .id(SEANCE_ID).date(LocalDate.now().plusDays(3))
                .heureDebut(LocalTime.of(8, 0)).heureFin(LocalTime.of(10, 0))
                .matiere(matiere).classe(classe).salle(salle).enseignant(enseignant)
                .statut(StatutSeance.A_FAIRE)
                .build();
    }

    private RattrapageRequest buildRequest() {
        RattrapageRequest req = new RattrapageRequest();
        req.setSeanceId(SEANCE_ID);
        req.setDateSouhaitee(LocalDate.now().plusDays(7));
        req.setHeureSouhaitee(LocalTime.of(14, 0));
        req.setMotif("Absence pour formation");
        return req;
    }

    private DemandeRattrapage buildDemande(StatutDemande statut) {
        return DemandeRattrapage.builder()
                .id(DEMANDE_ID)
                .enseignant(enseignant)
                .matiere(matiere)
                .classe(classe)
                .dateSouhaitee(LocalDate.now().plusDays(7))
                .heureSouhaitee(LocalTime.of(14, 0))
                .motif("Absence pour formation")
                .statut(statut)
                .build();
    }

    // =========================================================================
    // creerDemande
    // =========================================================================

    @Test
    void creerDemande_devraitRetournerDemandeEnAttente() {
        DemandeRattrapage saved = buildDemande(StatutDemande.EN_ATTENTE);

        when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
        when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
        when(demandeRepository.save(any())).thenReturn(saved);

        RattrapageResponse result = rattrapageService.creerDemande(USER_ID, buildRequest());

        assertThat(result).isNotNull();
        assertThat(result.getStatut()).isEqualTo(StatutDemande.EN_ATTENTE);
        assertThat(result.getEnseignantNom()).isEqualTo("Assale");
        assertThat(result.getMatiereLibelle()).isEqualTo("Algo avancée");
    }

    @Test
    void creerDemande_devraitEchouerSiEnseignantInexistant() {
        when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rattrapageService.creerDemande(USER_ID, buildRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void creerDemande_devraitEchouerSiSeanceInexistante() {
        when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
        when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rattrapageService.creerDemande(USER_ID, buildRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // accepterAvecSalle
    // =========================================================================

    @Test
    void accepterAvecSalle_devraitCreerUneSeanceDeRattrapage() {
        DemandeRattrapage demande = buildDemande(StatutDemande.EN_ATTENTE);
        Seance seanceRattrapage = Seance.builder()
                .id(99L).date(demande.getDateSouhaitee())
                .heureDebut(demande.getHeureSouhaitee())
                .heureFin(demande.getHeureSouhaitee().plusHours(2))
                .matiere(matiere).classe(classe).salle(salle).enseignant(enseignant)
                .type(TypeSeance.RATTRAPAGE).statut(StatutSeance.A_FAIRE)
                .build();

        when(demandeRepository.findById(DEMANDE_ID)).thenReturn(Optional.of(demande));
        when(seanceRepository.save(any())).thenReturn(seanceRattrapage);
        when(demandeRepository.save(any())).thenAnswer(inv -> {
            DemandeRattrapage d = inv.getArgument(0);
            d.setSeanceRattrapage(seanceRattrapage);
            return d;
        });

        RattrapageResponse result = rattrapageService.accepterAvecSalle(DEMANDE_ID, salle);

        assertThat(result.getStatut()).isEqualTo(StatutDemande.ACCEPTE);
        assertThat(result.getSeanceRattrapageId()).isEqualTo(99L);
    }

    @Test
    void accepterAvecSalle_devraitEchouerSiDemandeDejaTraitee() {
        DemandeRattrapage demande = buildDemande(StatutDemande.ACCEPTE); // déjà acceptée

        when(demandeRepository.findById(DEMANDE_ID)).thenReturn(Optional.of(demande));

        assertThatThrownBy(() -> rattrapageService.accepterAvecSalle(DEMANDE_ID, salle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deja ete traitee");
    }

    @Test
    void accepterAvecSalle_devraitEchouerSiDemandeInexistante() {
        when(demandeRepository.findById(DEMANDE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rattrapageService.accepterAvecSalle(DEMANDE_ID, salle))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // refuser
    // =========================================================================

    @Test
    void refuser_devraitPasserStatutARefuse() {
        DemandeRattrapage demande = buildDemande(StatutDemande.EN_ATTENTE);

        when(demandeRepository.findById(DEMANDE_ID)).thenReturn(Optional.of(demande));
        when(demandeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RattrapageResponse result = rattrapageService.refuser(DEMANDE_ID);

        assertThat(result.getStatut()).isEqualTo(StatutDemande.REFUSE);
    }

    @Test
    void refuser_devraitEchouerSiDemandeDejaTraitee() {
        DemandeRattrapage demande = buildDemande(StatutDemande.REFUSE); // déjà refusée

        when(demandeRepository.findById(DEMANDE_ID)).thenReturn(Optional.of(demande));

        assertThatThrownBy(() -> rattrapageService.refuser(DEMANDE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deja ete traitee");
    }

    // =========================================================================
    // getMesDemandes / getAllDemandes
    // =========================================================================

    @Test
    void getMesDemandes_devraitRetournerListeVide() {
        when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
        when(demandeRepository.findByEnseignantIdOrderByDateCreationDesc(enseignant.getId()))
                .thenReturn(List.of());

        assertThat(rattrapageService.getMesDemandes(USER_ID)).isEmpty();
    }

    @Test
    void getDemandesEnAttente_devraitRetournerSeulementEnAttente() {
        DemandeRattrapage d = buildDemande(StatutDemande.EN_ATTENTE);

        when(demandeRepository.findByStatutOrderByDateCreationDesc(StatutDemande.EN_ATTENTE))
                .thenReturn(List.of(d));

        List<RattrapageResponse> result = rattrapageService.getDemandesEnAttente();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatut()).isEqualTo(StatutDemande.EN_ATTENTE);
    }
}
