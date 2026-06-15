package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.EmargementRequest;
import ci.esatic.sigep.dto.response.EmargementResponse;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.EmargementRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmargementServiceTest {

    @Mock private EmargementRepository emargementRepository;
    @Mock private SeanceRepository seanceRepository;
    @Mock private EnseignantRepository enseignantRepository;
    @Mock private QrCodeService qrCodeService;

    @InjectMocks private EmargementService emargementService;

    private static final Long USER_ID   = 1L;
    private static final Long SEANCE_ID = 10L;
    private static final String SIGNATURE_VALIDE = "aGVsbG8="; // "hello" en Base64

    /**
     * Heure fixe pour les tests — 10h00 du matin, loin de minuit
     * pour éviter tout wrap-around dans LocalTime.plusHours/minusHours.
     */
    private static final LocalTime FIXED_NOW = LocalTime.of(10, 0);

    private Enseignant enseignant;
    private Salle salle;
    private Matiere matiere;
    private Classe classe;

    @BeforeEach
    void setUp() {
        Role role = new Role(1, ERole.ROLE_ENSEIGNANT);
        User user = User.builder()
                .id(USER_ID).email("prof@esatic.ci").password("encoded").roles(Set.of(role))
                .build();

        salle    = Salle.builder().id(1L).code("A101").batiment("A").capacite(30).build();
        matiere  = Matiere.builder().id(1L).code("INF301").libelle("Algo avancée").build();
        classe   = Classe.builder().id(1L).code("L3GL").libelle("L3 Génie Logiciel").filiere("GL").build();
        enseignant = Enseignant.builder()
                .id(5L).matricule("ENS001").nom("Assale").prenom("Jean")
                .statut(StatutEnseignant.VALIDATED).user(user)
                .build();
    }

    /**
     * Séance dont les horaires encadrent FIXED_NOW=10h00 :
     * debutAutorise = 9h00 - 15min = 8h45 ≤ 10h00 ≤ 11h00 + 30min = 11h30 = finAutorisee ✓
     */
    private Seance seanceDansLaFenetre() {
        return Seance.builder()
                .id(SEANCE_ID).date(LocalDate.now())
                .heureDebut(LocalTime.of(9, 0)).heureFin(LocalTime.of(11, 0))
                .matiere(matiere).classe(classe).salle(salle).enseignant(enseignant)
                .statut(StatutSeance.A_FAIRE).build();
    }

    private EmargementRequest buildRequest(String qrToken, String signature) {
        EmargementRequest req = new EmargementRequest();
        req.setSeanceId(SEANCE_ID);
        req.setQrToken(qrToken);
        req.setSignatureBase64(signature);
        return req;
    }

    // =========================================================================
    // Cas de succès
    // =========================================================================

    @Test
    void emarger_devraitReussirAvecDonneesValides() {
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);

            Seance seance = seanceDansLaFenetre();
            Emargement saved = Emargement.builder()
                    .id(1L).seance(seance).enseignant(enseignant)
                    .dateHeure(LocalDateTime.now()).signatureBase64(SIGNATURE_VALIDE).build();

            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
            when(qrCodeService.validateQrToken("valid-token", "A101")).thenReturn(true);
            when(seanceRepository.save(any())).thenReturn(seance);
            when(emargementRepository.save(any())).thenReturn(saved);

            EmargementResponse result = emargementService.emarger(USER_ID, buildRequest("valid-token", SIGNATURE_VALIDE));

            assertThat(result).isNotNull();
            assertThat(result.getSeanceId()).isEqualTo(SEANCE_ID);
            assertThat(result.getEnseignantNom()).isEqualTo("Assale");
        }
    }

    // =========================================================================
    // Règle 1 : la séance appartient à cet enseignant
    // =========================================================================

    @Test
    void emarger_devraitEchouerSiSeanceNAppartientPasEnseignant() {
        Enseignant autreEnseignant = Enseignant.builder()
                .id(99L).matricule("ENS999").nom("Autre").prenom("Prof").build();
        Seance seance = Seance.builder()
                .id(SEANCE_ID).date(LocalDate.now())
                .heureDebut(LocalTime.of(9, 0)).heureFin(LocalTime.of(11, 0))
                .enseignant(autreEnseignant).salle(salle).matiere(matiere).classe(classe).build();

        when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
        when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));

        assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("t", SIGNATURE_VALIDE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne vous appartient pas");
    }

    // =========================================================================
    // Règle 2 : séance aujourd'hui
    // =========================================================================

    @Test
    void emarger_devraitEchouerSiSeancePasAujourdhui() {
        Seance seance = Seance.builder()
                .id(SEANCE_ID).date(LocalDate.now().minusDays(1)) // hier
                .heureDebut(LocalTime.of(9, 0)).heureFin(LocalTime.of(11, 0))
                .enseignant(enseignant).salle(salle).matiere(matiere).classe(classe).build();

        when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
        when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));

        assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("t", SIGNATURE_VALIDE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pas prevue aujourd'hui");
    }

    // =========================================================================
    // Règle 3 : unicité de l'émargement
    // =========================================================================

    @Test
    void emarger_devraitEchouerSiSeanceDejaEmargee() {
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);

            Seance seance = seanceDansLaFenetre();
            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(true);

            assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("t", SIGNATURE_VALIDE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deja ete emargee");
        }
    }

    // =========================================================================
    // Règle 4 : fenêtre temporelle
    // =========================================================================

    @Test
    void emarger_devraitEchouerSiTropTot() {
        // FIXED_NOW=10h00, heureDebut=13h00 → debutAutorise=12h45, 10h00 < 12h45 → trop tôt
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);

            Seance seance = Seance.builder()
                    .id(SEANCE_ID).date(LocalDate.now())
                    .heureDebut(LocalTime.of(13, 0)).heureFin(LocalTime.of(15, 0))
                    .enseignant(enseignant).salle(salle).matiere(matiere).classe(classe).build();

            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);

            assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("t", SIGNATURE_VALIDE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ne commence pas encore");
        }
    }

    @Test
    void emarger_devraitEchouerSiDelaiDepasse() {
        // FIXED_NOW=10h00, heureFin=07h00 → finAutorisee=07h30, 10h00 > 07h30 → délai dépassé
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);

            Seance seance = Seance.builder()
                    .id(SEANCE_ID).date(LocalDate.now())
                    .heureDebut(LocalTime.of(5, 0)).heureFin(LocalTime.of(7, 0))
                    .enseignant(enseignant).salle(salle).matiere(matiere).classe(classe).build();

            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);

            assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("t", SIGNATURE_VALIDE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("delai depasse");
        }
    }

    // =========================================================================
    // Règle 5 : token QR valide
    // =========================================================================

    @Test
    void emarger_devraitEchouerSiQrCodeInvalide() {
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);

            Seance seance = seanceDansLaFenetre();
            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
            when(qrCodeService.validateQrToken("mauvais-token", "A101")).thenReturn(false);

            assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("mauvais-token", SIGNATURE_VALIDE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("QR Code invalide");
        }
    }

    // =========================================================================
    // Validation signature
    // =========================================================================

    @Test
    void emarger_devraitEchouerSiSignatureVide() {
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);

            Seance seance = seanceDansLaFenetre();
            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
            when(qrCodeService.validateQrToken(any(), any())).thenReturn(true);

            assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("valid-token", "")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("signature est obligatoire");
        }
    }

    @Test
    void emarger_devraitEchouerSiSignatureFormatInvalide() {
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);

            Seance seance = seanceDansLaFenetre();
            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
            when(qrCodeService.validateQrToken(any(), any())).thenReturn(true);

            assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("valid-token", "pas@du@base64!!")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("format Base64 incorrect");
        }
    }

    // =========================================================================
    // Ressource introuvable
    // =========================================================================

    @Test
    void emarger_devraitEchouerSiEnseignantInexistant() {
        when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("t", SIGNATURE_VALIDE)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void emarger_devraitEchouerSiSeanceInexistante() {
        when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
        when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("t", SIGNATURE_VALIDE)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // Historique
    // =========================================================================

    @Test
    void getHistorique_devraitRetournerListeVide() {
        when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
        when(emargementRepository.findByEnseignantId(enseignant.getId())).thenReturn(List.of());

        List<EmargementResponse> result = emargementService.getHistorique(USER_ID);

        assertThat(result).isEmpty();
    }
}
