package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import ci.esatic.sigep.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test d'intégration de l'émargement : flux complet anti-fraude de bout en bout
 * (JWT enseignant réel + QR token réel signé + persistance H2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EmargementIntegrationTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private MatiereRepository matiereRepository;
    @Autowired private ClasseRepository classeRepository;
    @Autowired private SalleRepository salleRepository;
    @Autowired private SeanceRepository seanceRepository;
    @Autowired private EmargementRepository emargementRepository;

    private static final String SIGNATURE = "aGVsbG8="; // "hello" en Base64
    private static final String SALLE_CODE = "B203";

    private String jwt;
    private Long seanceId;
    private User user;

    @BeforeEach
    void setUp() {
        // Fenêtre horaire : on évite l'extrême minuit pour ne pas faire boucler LocalTime
        LocalTime now = LocalTime.now();
        assumeTrue(now.isAfter(LocalTime.of(0, 20)) && now.isBefore(LocalTime.of(23, 25)),
                "Test ignoré dans la fenêtre minuit (calcul de tolérance horaire)");

        Role role = roleRepository.findByName(ERole.ROLE_ENSEIGNANT)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ENSEIGNANT)));

        user = userRepository.save(User.builder()
                .email("prof.integration@esatic.ci")
                .password(passwordEncoder.encode("Secret@2026"))
                .roles(Set.of(role))
                .build());

        Enseignant enseignant = enseignantRepository.save(Enseignant.builder()
                .matricule("ENS-INT-001").nom("Kone").prenom("Awa")
                .statut(StatutEnseignant.VALIDATED).user(user)
                .build());

        Matiere matiere = matiereRepository.save(Matiere.builder().code("BDD").libelle("Bases de données").build());
        Classe classe = classeRepository.save(Classe.builder().code("L2RT").libelle("L2 Réseaux").filiere("RT").niveau(2).build());
        Salle salle = salleRepository.save(Salle.builder().code(SALLE_CODE).batiment("B").capacite(40).build());

        Seance seance = seanceRepository.save(Seance.builder()
                .date(LocalDate.now())
                .heureDebut(now)
                .heureFin(now.plusMinutes(1))
                .matiere(matiere).classe(classe).salle(salle).enseignant(enseignant)
                .type(TypeSeance.NORMALE).statut(StatutSeance.A_FAIRE)
                .build());
        seanceId = seance.getId();

        jwt = jwtService.generateToken(user);
    }

    @Test
    void emargement_completDevraitReussirEtPersister() throws Exception {
        String qrToken = jwtService.generateQrToken(SALLE_CODE, 30_000L);
        String body = objectMapper.writeValueAsString(Map.of(
                "seanceId", seanceId, "qrToken", qrToken, "signatureBase64", SIGNATURE));

        mockMvc.perform(post("/api/emargements")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.seanceId").value(seanceId));

        // Vérifie la persistance réelle et le passage du statut à EMARGE
        assertThat(emargementRepository.existsBySeanceId(seanceId)).isTrue();
        assertThat(seanceRepository.findById(seanceId).orElseThrow().getStatut())
                .isEqualTo(StatutSeance.EMARGE);
    }

    @Test
    void emargement_devraitEchouerAvecQrInvalide() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "seanceId", seanceId, "qrToken", "qr-bidon", "signatureBase64", SIGNATURE));

        mockMvc.perform(post("/api/emargements")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        assertThat(emargementRepository.existsBySeanceId(seanceId)).isFalse();
    }

    @Test
    void emargement_devraitEchouerSansToken() throws Exception {
        String qrToken = jwtService.generateQrToken(SALLE_CODE, 30_000L);
        String body = objectMapper.writeValueAsString(Map.of(
                "seanceId", seanceId, "qrToken", qrToken, "signatureBase64", SIGNATURE));

        mockMvc.perform(post("/api/emargements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }
}
