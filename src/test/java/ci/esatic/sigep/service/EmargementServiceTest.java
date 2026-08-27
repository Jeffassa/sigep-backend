package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.EmargementRequest;
import ci.esatic.sigep.dto.request.EmargementHorsLigneRequest;
import ci.esatic.sigep.dto.response.EmargementResponse;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.EmargementRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import ci.esatic.sigep.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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
    @Mock private ci.esatic.sigep.security.QrReplayGuard qrReplayGuard;
    @Mock private ci.esatic.sigep.repository.EtablissementRepository etablissementRepository;
    // Mapper RÉEL (impl MapStruct générée) pour que les réponses soient effectivement mappées.
    @org.mockito.Spy private ci.esatic.sigep.mapper.EmargementMapper emargementMapper =
            new ci.esatic.sigep.mapper.EmargementMapperImpl();

    @InjectMocks private EmargementService emargementService;

    private static final Long USER_ID   = 1L;
    private static final Long SEANCE_ID = 10L;
    private static final Long ETAB_ID   = 7L;
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

        salle    = Salle.builder().id(1L).libelle("A101").batiment("A").capacite(30).build();
        matiere  = Matiere.builder().id(1L).libelle("Algo avancée").build();
        classe   = Classe.builder().id(1L).libelle("L3 Génie Logiciel").filiere("GL").build();
        enseignant = Enseignant.builder()
                .id(5L).matricule("ENS001").nom("Assale").prenom("Jean")
                .statut(StatutEnseignant.VALIDATED).user(user)
                .etablissementId(ETAB_ID)
                .build();

        // E1/E7 : l'émargement lit le fuseau + les tolérances de l'établissement (défauts).
        Etablissement etab = Etablissement.builder().nom("ESATIC").slug("esatic").build();
        etab.setId(ETAB_ID);
        lenient().when(etablissementRepository.findById(ETAB_ID)).thenReturn(Optional.of(etab));
    }

    /** QR universel VALIDE et rattaché à l'établissement de l'enseignant (lecture unique). */
    private void stubQrValide() {
        when(qrCodeService.lireQrUniversel(any()))
                .thenReturn(new JwtService.QrUniversel(true, ETAB_ID, "jti-ok"));
    }

    /** QR universel INVALIDE/expiré. */
    private void stubQrInvalide() {
        when(qrCodeService.lireQrUniversel(any()))
                .thenReturn(new JwtService.QrUniversel(false, null, null));
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

    private EmargementHorsLigneRequest buildHorsLigne(String qrToken) {
        EmargementHorsLigneRequest req = new EmargementHorsLigneRequest();
        req.setSeanceId(SEANCE_ID);
        req.setSignatureBase64(SIGNATURE_VALIDE);
        req.setQrToken(qrToken);
        return req;
    }

    /** QR universel « différé » valide, rattaché à l'établissement, émis à {@code emisLe}. */
    private void stubQrDiffereValide(Instant emisLe) {
        when(qrCodeService.lireQrUniverselDiffere(any()))
                .thenReturn(new JwtService.QrUniverselDiffere(true, ETAB_ID, "jti-hl", emisLe));
    }

    /** Instant du jour à l'heure {@code h} dans le fuseau du tenant (Africa/Abidjan par défaut). */
    private Instant scanLeJourA(int h, int min) {
        return LocalDate.now().atTime(h, min).atZone(ZoneId.of("Africa/Abidjan")).toInstant();
    }

    // =========================================================================
    // Cas de succès
    // =========================================================================

    @Test
    void emarger_devraitReussirAvecDonneesValides() {
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);
            // E1 : l'émargement évalue l'heure dans le fuseau du tenant → mocker la surcharge ZoneId.
            lt.when(() -> LocalTime.now(any(java.time.ZoneId.class))).thenReturn(FIXED_NOW);

            Seance seance = seanceDansLaFenetre();
            Emargement saved = Emargement.builder()
                    .id(1L).seance(seance).enseignant(enseignant)
                    .dateHeure(LocalDateTime.now()).signatureBase64(SIGNATURE_VALIDE).build();

            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
            stubQrValide();
            when(qrReplayGuard.tryConsume(any(), any())).thenReturn(true);
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
                .isInstanceOf(MetierException.class)
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
                .isInstanceOf(MetierException.class)
                .hasMessageContaining("pas prevue aujourd'hui");
    }

    // =========================================================================
    // Règle 3 : unicité de l'émargement
    // =========================================================================

    @Test
    void emarger_devraitEchouerSiSeanceDejaEmargee() {
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);
            // E1 : l'émargement évalue l'heure dans le fuseau du tenant → mocker la surcharge ZoneId.
            lt.when(() -> LocalTime.now(any(java.time.ZoneId.class))).thenReturn(FIXED_NOW);

            Seance seance = seanceDansLaFenetre();
            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(true);

            assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("t", SIGNATURE_VALIDE)))
                    .isInstanceOf(MetierException.class)
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
            // E1 : l'émargement évalue l'heure dans le fuseau du tenant → mocker la surcharge ZoneId.
            lt.when(() -> LocalTime.now(any(java.time.ZoneId.class))).thenReturn(FIXED_NOW);

            Seance seance = Seance.builder()
                    .id(SEANCE_ID).date(LocalDate.now())
                    .heureDebut(LocalTime.of(13, 0)).heureFin(LocalTime.of(15, 0))
                    .enseignant(enseignant).salle(salle).matiere(matiere).classe(classe).build();

            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);

            assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("t", SIGNATURE_VALIDE)))
                    .isInstanceOf(MetierException.class)
                    .hasMessageContaining("Trop tot");
        }
    }

    @Test
    void emarger_devraitReussirEnRetardSiSeanceTerminee() {
        // FIXED_NOW=10h00, heureFin=07h00 → séance terminée : émargement tardif AUTORISÉ, marqué "en retard"
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);
            // E1 : l'émargement évalue l'heure dans le fuseau du tenant → mocker la surcharge ZoneId.
            lt.when(() -> LocalTime.now(any(java.time.ZoneId.class))).thenReturn(FIXED_NOW);

            Seance seance = Seance.builder()
                    .id(SEANCE_ID).date(LocalDate.now())
                    .heureDebut(LocalTime.of(5, 0)).heureFin(LocalTime.of(7, 0))
                    .enseignant(enseignant).salle(salle).matiere(matiere).classe(classe)
                    .statut(StatutSeance.A_FAIRE).build();

            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
            stubQrValide();
            when(qrReplayGuard.tryConsume(any(), any())).thenReturn(true);
            when(seanceRepository.save(any())).thenReturn(seance);
            when(emargementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            EmargementResponse result = emargementService.emarger(USER_ID, buildRequest("valid-token", SIGNATURE_VALIDE));

            assertThat(result).isNotNull();
            assertThat(result.isEnRetard()).isTrue();
        }
    }

    @Test
    void emarger_devraitEchouerSiQrUniverselExpire() {
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);
            // E1 : l'émargement évalue l'heure dans le fuseau du tenant → mocker la surcharge ZoneId.
            lt.when(() -> LocalTime.now(any(java.time.ZoneId.class))).thenReturn(FIXED_NOW);

            Seance seance = seanceDansLaFenetre();
            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
            stubQrInvalide();

            assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("expire", SIGNATURE_VALIDE)))
                    .isInstanceOf(MetierException.class)
                    .hasMessageContaining("QR Code invalide");
        }
    }

    // =========================================================================
    // Règle 5 : token QR valide
    // =========================================================================

    @Test
    void emarger_devraitEchouerSiQrCodeInvalide() {
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);
            // E1 : l'émargement évalue l'heure dans le fuseau du tenant → mocker la surcharge ZoneId.
            lt.when(() -> LocalTime.now(any(java.time.ZoneId.class))).thenReturn(FIXED_NOW);

            Seance seance = seanceDansLaFenetre();
            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
            stubQrInvalide();

            assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("mauvais-token", SIGNATURE_VALIDE)))
                    .isInstanceOf(MetierException.class)
                    .hasMessageContaining("QR Code invalide");
        }
    }

    // =========================================================================
    // Règle 6 : anti-rejeu du token QR
    // =========================================================================

    @Test
    void emarger_devraitEchouerSiQrDejaUtilise() {
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);
            // E1 : l'émargement évalue l'heure dans le fuseau du tenant → mocker la surcharge ZoneId.
            lt.when(() -> LocalTime.now(any(java.time.ZoneId.class))).thenReturn(FIXED_NOW);

            Seance seance = seanceDansLaFenetre();
            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
            stubQrValide();
            when(qrReplayGuard.tryConsume(any(), any())).thenReturn(false); // déjà utilisé

            assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("valid-token", SIGNATURE_VALIDE)))
                    .isInstanceOf(MetierException.class)
                    .hasMessageContaining("deja ete utilise");
        }
    }

    // =========================================================================
    // Émargement hors-ligne (sans QR)
    // =========================================================================

    @Test
    void emargerHorsLigne_devraitExigerQrEtMettreEnAttenteValidation() {
        Seance seance = seanceDansLaFenetre(); // aujourd'hui, 9h-11h
        when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
        when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
        when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
        stubQrDiffereValide(scanLeJourA(10, 0)); // QR émis pendant la fenêtre
        when(qrReplayGuard.tryConsume(any(), any())).thenReturn(true);
        when(emargementRepository.countHorsLigneByEnseignantEtPeriode(any(), any(), any())).thenReturn(0L);
        when(seanceRepository.save(any())).thenReturn(seance);
        when(emargementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EmargementResponse r = emargementService.emargerHorsLigne(USER_ID, buildHorsLigne("qr-token"));

        assertThat(r).isNotNull();
        assertThat(r.isHorsLigne()).isTrue();
        assertThat(r.isValide()).isFalse();                                  // présence à confirmer
        assertThat(seance.getStatut()).isEqualTo(StatutSeance.EN_ATTENTE_VALIDATION);
    }

    @Test
    void emargerHorsLigne_devraitEchouerSansQrValide() {
        Seance seance = seanceDansLaFenetre();
        when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
        when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
        when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
        when(qrCodeService.lireQrUniverselDiffere(any()))
                .thenReturn(JwtService.QrUniverselDiffere.invalide());

        assertThatThrownBy(() -> emargementService.emargerHorsLigne(USER_ID, buildHorsLigne("bad")))
                .isInstanceOf(MetierException.class)
                .hasMessageContaining("QR invalide");
    }

    @Test
    void emargerHorsLigne_devraitEchouerSiQrScanneHorsFenetre() {
        Seance seance = seanceDansLaFenetre(); // 9h-11h → fenêtre [8h45, 11h30]
        when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
        when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
        when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
        stubQrDiffereValide(scanLeJourA(18, 0)); // QR émis à 18h → hors fenêtre

        assertThatThrownBy(() -> emargementService.emargerHorsLigne(USER_ID, buildHorsLigne("qr")))
                .isInstanceOf(MetierException.class)
                .hasMessageContaining("hors de la fenetre");
    }

    @Test
    void emargerHorsLigne_devraitEchouerSiPlafondAtteint() {
        Seance seance = seanceDansLaFenetre();
        when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
        when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
        when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
        stubQrDiffereValide(scanLeJourA(10, 0));
        when(qrReplayGuard.tryConsume(any(), any())).thenReturn(true);
        // Déjà 5 hors-ligne ce mois-ci (plafond par défaut = 5) → refus.
        when(emargementRepository.countHorsLigneByEnseignantEtPeriode(any(), any(), any())).thenReturn(5L);

        assertThatThrownBy(() -> emargementService.emargerHorsLigne(USER_ID, buildHorsLigne("qr")))
                .isInstanceOf(MetierException.class)
                .hasMessageContaining("Plafond");
    }

    @Test
    void validerHorsLigne_devraitConfirmerLaPresence() {
        Seance seance = seanceDansLaFenetre();
        seance.setStatut(StatutSeance.EN_ATTENTE_VALIDATION);
        Emargement em = Emargement.builder()
                .id(3L).seance(seance).enseignant(enseignant)
                .horsLigne(true).valide(false).build();
        when(emargementRepository.findById(3L)).thenReturn(Optional.of(em));
        when(seanceRepository.save(any())).thenReturn(seance);
        when(emargementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        emargementService.validerHorsLigne(3L);

        assertThat(em.isValide()).isTrue();
        assertThat(seance.getStatut()).isEqualTo(StatutSeance.EMARGE);
    }

    @Test
    void refuserHorsLigne_devraitLibererLaSeance() {
        Seance seance = seanceDansLaFenetre();
        seance.setStatut(StatutSeance.EN_ATTENTE_VALIDATION);
        Emargement em = Emargement.builder()
                .id(4L).seance(seance).enseignant(enseignant)
                .horsLigne(true).valide(false).build();
        when(emargementRepository.findById(4L)).thenReturn(Optional.of(em));
        when(seanceRepository.save(any())).thenReturn(seance);

        emargementService.refuserHorsLigne(4L);

        assertThat(seance.getStatut()).isEqualTo(StatutSeance.A_FAIRE);
        verify(emargementRepository).delete(em);
    }

    // =========================================================================
    // Validation signature
    // =========================================================================

    @Test
    void emarger_devraitReussirSansSignature() {
        // La signature est optionnelle : le QR est la preuve de présence (C2).
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);
            lt.when(() -> LocalTime.now(any(java.time.ZoneId.class))).thenReturn(FIXED_NOW);

            Seance seance = seanceDansLaFenetre();
            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
            stubQrValide();
            when(qrReplayGuard.tryConsume(any(), any())).thenReturn(true);
            when(seanceRepository.save(any())).thenReturn(seance);
            when(emargementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            EmargementResponse r = emargementService.emarger(USER_ID, buildRequest("valid-token", ""));

            assertThat(r).isNotNull();
            assertThat(r.getSeanceId()).isEqualTo(SEANCE_ID);
        }
    }

    @Test
    void emarger_devraitEchouerSiSignatureFormatInvalide() {
        try (MockedStatic<LocalTime> lt = mockStatic(LocalTime.class, CALLS_REAL_METHODS)) {
            lt.when(LocalTime::now).thenReturn(FIXED_NOW);
            // E1 : l'émargement évalue l'heure dans le fuseau du tenant → mocker la surcharge ZoneId.
            lt.when(() -> LocalTime.now(any(java.time.ZoneId.class))).thenReturn(FIXED_NOW);

            Seance seance = seanceDansLaFenetre();
            when(enseignantRepository.findByUserId(USER_ID)).thenReturn(Optional.of(enseignant));
            when(seanceRepository.findById(SEANCE_ID)).thenReturn(Optional.of(seance));
            when(emargementRepository.existsBySeanceId(SEANCE_ID)).thenReturn(false);
            stubQrValide();
            when(qrReplayGuard.tryConsume(any(), any())).thenReturn(true);

            assertThatThrownBy(() -> emargementService.emarger(USER_ID, buildRequest("valid-token", "pas@du@base64!!")))
                    .isInstanceOf(MetierException.class)
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
