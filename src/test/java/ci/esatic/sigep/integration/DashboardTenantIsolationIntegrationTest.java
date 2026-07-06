package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Régression (bug découvert avec le 2e établissement inscrit) : le dashboard admin renvoyait
 * 500 (AccesTenantRefuseException) car le TenantInterceptor activait le filtre Hibernate sur
 * une session JETABLE (il s'exécutait AVANT OpenEntityManagerInView) — les requêtes de la page
 * tournaient donc SANS filtre tenant, findAll() chargeait les enseignants de TOUS les
 * établissements et le garde-fou @PostLoad refusait la première entité étrangère.
 *
 * Ce test passe par la vraie pile MVC (intercepteurs + OSIV), volontairement SANS
 * @Transactional : une transaction de test lierait une session avant les intercepteurs et
 * masquerait le bug d'ordre. Nettoyage manuel en conséquence (base H2 partagée).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardTenantIsolationIntegrationTest {

    // Mocké : évite de commiter l'admin/établissement par défaut dans la base H2 partagée.
    @MockBean
    private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Etablissement etabA;
    private Etablissement etabB;
    private Enseignant enseignantDeB;
    private User adminDeA;

    @BeforeEach
    void setUp() {
        Role roleAdmin = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));

        long n = System.nanoTime();
        etabA = etablissementRepository.save(
                Etablissement.builder().nom("Etab A").slug("dash-a-" + n).build());
        etabB = etablissementRepository.save(
                Etablissement.builder().nom("Etab B").slug("dash-b-" + n).build());

        // Enseignant de B : ne doit JAMAIS être chargé pendant la navigation de l'admin de A.
        Enseignant e = Enseignant.builder()
                .matricule("FUITE-" + n).nom("FuiteInterTenant").prenom("Detectable").build();
        e.setEtablissementId(etabB.getId());
        enseignantDeB = enseignantRepository.save(e);

        adminDeA = userRepository.save(User.builder()
                .email("admin-dash-" + n + "@test.local")
                .password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin)))
                .etablissement(etabA)
                .build());
    }

    @AfterEach
    void tearDown() {
        enseignantRepository.deleteById(enseignantDeB.getId());
        userRepository.deleteById(adminDeA.getId());
        etablissementRepository.deleteById(etabA.getId());
        etablissementRepository.deleteById(etabB.getId());
    }

    @Test
    void dashboard_avecPlusieursEtablissements_rendSansErreurEtIsole() throws Exception {
        mockMvc.perform(get("/admin/dashboard").with(user(adminDeA)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("FuiteInterTenant"))));
    }
}
