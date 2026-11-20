package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.Seance;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.StatutSeance;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.security.JwtService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Une séance émargée devient des heures payées : celle qu'un enseignant importe lui-même ne doit
 * ni pouvoir être émargée, ni peser dans aucun calcul, avant l'accord de la scolarité.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SeanceAValiderTest {

    @MockitoBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private SeanceRepository seanceRepository;
    @Autowired private JwtService jwtService;
    @Autowired private jakarta.persistence.EntityManager em;

    private Enseignant enseignant;
    private String jeton;
    private User admin;
    private User adminAilleurs;

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        Etablissement ecole = etablissementRepository.save(Etablissement.builder()
                .nom("Ecole").slug("val-" + n).build());
        Etablissement autre = etablissementRepository.save(Etablissement.builder()
                .nom("Autre").slug("val-b-" + n).build());
        Role roleEns = role(ERole.ROLE_ENSEIGNANT);
        Role roleAdmin = role(ERole.ROLE_ADMIN);

        User compte = userRepository.save(User.builder().email("ens-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleEns))).etablissement(ecole).build());
        enseignant = enseignantRepository.save(Enseignant.builder().matricule("M-" + n).nom("Kone").prenom("Awa")
                .statut(StatutEnseignant.VALIDATED).user(compte).etablissementId(ecole.getId()).build());
        jeton = jwtService.generateToken(compte);

        admin = userRepository.save(User.builder().email("adm-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin))).etablissement(ecole).build());
        adminAilleurs = userRepository.save(User.builder().email("adm-b-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin))).etablissement(autre).build());
    }

    private Role role(ERole nom) {
        return roleRepository.findByName(nom).orElseGet(() -> roleRepository.save(new Role(null, nom)));
    }

    /** L'enseignant importe une séance (hier, pour qu'elle compte comme passée) et on la retrouve. */
    private Seance importer(LocalDate date) throws Exception {
        String csv = "DATE;HEURE_DEBUT;HEURE_FIN;MATIERE;CLASSE;SALLE\n"
                + date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ";08:00;20:00;Inventee;L2;B12\n";
        mockMvc.perform(multipart("/api/import/mon-planning")
                        .file(new MockMultipartFile("file", "planning.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalImporte").value(1));
        return seanceRepository.findByEnseignantIdAndDateOrderByHeureDebutAsc(enseignant.getId(), date).get(0);
    }

    @Test
    void uneSeanceImporteeParLEnseignant_attendLaScolarite() throws Exception {
        assertThat(importer(LocalDate.now()).getStatut()).isEqualTo(StatutSeance.A_VALIDER);

        mockMvc.perform(get("/api/seances/a-emarger").header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void elleNePeutPasEtreEmargee() throws Exception {
        Seance s = importer(LocalDate.now());

        mockMvc.perform(post("/api/emargements")
                        .header("Authorization", "Bearer " + jeton)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("seanceId", s.getId(), "qrToken", "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("SEANCE_A_VALIDER"));

        mockMvc.perform(post("/api/emargements/hors-ligne")
                        .header("Authorization", "Bearer " + jeton)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "seanceId", s.getId(), "qrToken", "x", "signatureBase64", "U0lH"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("SEANCE_A_VALIDER"));
    }

    @Test
    void elleNePeseDansAucunCalcul() throws Exception {
        LocalDate hier = LocalDate.now().minusDays(1);
        importer(hier);

        assertThat(seanceRepository.countPlanifieesParEnseignant(enseignant.getId())).isZero();
        assertThat(seanceRepository.countByEnseignantIdAndPeriode(enseignant.getId(), hier, hier)).isZero();
        assertThat(seanceRepository.countAllByDate(hier)).isZero();
        assertThat(seanceRepository.findSeancesNonEmargees(LocalDate.now(), LocalTime.MAX)
                .stream().map(Seance::getEnseignant).toList()).doesNotContain(enseignant);
        assertThat(seanceRepository.projectionPeriode(hier, hier)).isEmpty();
    }

    @Test
    void laScolariteLaccepte_etElleDevientEmargeable() throws Exception {
        Seance s = importer(LocalDate.now());

        mockMvc.perform(post("/admin/seances/" + s.getId() + "/valider").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(seanceRepository.findById(s.getId()).orElseThrow().getStatut()).isEqualTo(StatutSeance.A_FAIRE);
    }

    @Test
    void laScolariteLEcarte_etElleDisparait() throws Exception {
        Seance s = importer(LocalDate.now());

        mockMvc.perform(post("/admin/seances/" + s.getId() + "/refuser").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(seanceRepository.findById(s.getId())).isEmpty();
    }

    @Test
    void toutAccepter_neTouchePasAuxAutresEtablissements() throws Exception {
        Seance s = importer(LocalDate.now());

        mockMvc.perform(post("/admin/seances/valider-tout").with(user(adminAilleurs)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(seanceRepository.findById(s.getId()).orElseThrow().getStatut()).isEqualTo(StatutSeance.A_VALIDER);

        mockMvc.perform(post("/admin/seances/valider-tout").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(seanceRepository.findById(s.getId()).orElseThrow().getStatut()).isEqualTo(StatutSeance.A_FAIRE);
    }

    @Test
    void unAdminDUnAutreEtablissement_nePeutPasLaccepter() throws Exception {
        Seance s = importer(LocalDate.now());
        // La garde tient au chargement (@PostLoad) : une vraie requête part d'une session vide,
        // alors que la transaction du test garde la séance en cache.
        em.flush();
        em.clear();

        mockMvc.perform(post("/admin/seances/" + s.getId() + "/valider").with(user(adminAilleurs)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(seanceRepository.findById(s.getId()).orElseThrow().getStatut()).isEqualTo(StatutSeance.A_VALIDER);
    }

    @Test
    void laFileApparaitDansLesAlertes() throws Exception {
        importer(LocalDate.now());

        String html = mockMvc.perform(get("/admin/alertes").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(List.of(html)).first().asString().contains("Séances importées par les enseignants");
    }
}
