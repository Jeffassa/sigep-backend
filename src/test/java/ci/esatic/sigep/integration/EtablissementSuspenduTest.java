package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.CleApi;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.CleApiRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.security.CleApiAuthFilter;
import ci.esatic.sigep.security.JwtService;
import ci.esatic.sigep.service.CleApiService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suspendre un établissement ferme toutes ses portes : la connexion, les jetons déjà délivrés,
 * les clés d'API et l'écran de salle. Le blocage ne valait jusqu'ici que pour /admin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EtablissementSuspenduTest {

    private static final String MDP = "Secret@2026";

    @MockitoBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private CleApiRepository cleApiRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private Etablissement ecole;
    private User compte;

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        ecole = etablissementRepository.save(Etablissement.builder()
                .nom("Ecole").slug("susp-" + n).plan(Plan.ENTERPRISE).kioskKey("cle-kiosque-" + n).build());
        Role role = roleRepository.findByName(ERole.ROLE_ENSEIGNANT)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ENSEIGNANT)));
        compte = userRepository.save(User.builder().email("susp-" + n + "@test.local")
                .password(passwordEncoder.encode(MDP))
                .roles(new HashSet<>(Set.of(role))).etablissement(ecole).build());
        enseignantRepository.save(Enseignant.builder().matricule("S-" + n).nom("Kone").prenom("Awa")
                .statut(StatutEnseignant.VALIDATED).user(compte).etablissementId(ecole.getId()).build());
    }

    private void desactiver() {
        ecole.setActif(false);
        etablissementRepository.saveAndFlush(ecole);
    }

    private void expirer() {
        ecole.setDateExpiration(LocalDate.now().minusDays(1));
        etablissementRepository.saveAndFlush(ecole);
    }

    private org.springframework.test.web.servlet.ResultActions connexion() throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", compte.getEmail(), "password", MDP))));
    }

    @Test
    void etablissementActif_laConnexionAboutit() throws Exception {
        connexion().andExpect(status().isOk());
    }

    @Test
    void etablissementDesactive_laConnexionEstRefusee() throws Exception {
        desactiver();
        connexion().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("suspendu")));
    }

    @Test
    void abonnementExpire_laConnexionEstRefusee() throws Exception {
        expirer();
        connexion().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("suspendu")));
    }

    @Test
    void unJetonDejaDelivre_cesseDOuvrirLApi() throws Exception {
        String jeton = jwtService.generateToken(compte);
        mockMvc.perform(get("/api/seances/aujourd-hui").header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk());

        desactiver();

        mockMvc.perform(get("/api/seances/aujourd-hui").header("Authorization", "Bearer " + jeton))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("suspendu")));
    }

    @Test
    void laDeconnexionResteOuverte() throws Exception {
        String jeton = jwtService.generateToken(compte);
        desactiver();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + jeton)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", "inconnu"))))
                .andExpect(status().isOk());
    }

    @Test
    void uneCleDApi_estRefuseeQuandLAccesEstSuspendu() throws Exception {
        String cle = "sigep_cle-de-test-" + System.nanoTime();
        cleApiRepository.saveAndFlush(CleApi.builder()
                .libelle("Test").empreinte(CleApiService.empreinte(cle)).prefixe("sigep_cle")
                .creeeLe(LocalDateTime.now()).etablissementId(ecole.getId()).build());
        mockMvc.perform(get("/api/public/ping").header(CleApiAuthFilter.ENTETE, cle))
                .andExpect(status().isOk());

        expirer();

        mockMvc.perform(get("/api/public/ping").header(CleApiAuthFilter.ENTETE, cle))
                .andExpect(status().isForbidden());
    }

    @Test
    void lEcranDeSalle_sEteintQuandLAccesEstSuspendu() throws Exception {
        mockMvc.perform(get("/api/qr/display").param("etab", ecole.getSlug()).param("key", ecole.getKioskKey()))
                .andExpect(status().isOk());

        expirer();

        mockMvc.perform(get("/api/qr/display").param("etab", ecole.getSlug()).param("key", ecole.getKioskKey()))
                .andExpect(status().isForbidden());
    }
}
