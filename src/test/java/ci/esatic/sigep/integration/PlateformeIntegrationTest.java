package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.User;
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

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Espace plateforme (super admin) : séparation stricte des rôles et vision globale.
 *  - le super admin voit TOUS les établissements clients (lecture cross-tenant voulue) ;
 *  - un admin d'établissement n'accède JAMAIS à /plateforme/** (403) ;
 *  - le super admin n'accède pas à l'admin d'établissement (/admin/** exige ROLE_ADMIN) ;
 *  - la prolongation d'abonnement est réservée au super admin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlateformeIntegrationTest {

    // Mocké : évite de commiter l'admin/établissement par défaut dans la base H2 partagée.
    @MockBean
    private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Etablissement plateforme;
    private Etablissement clientA;
    private Etablissement clientB;
    private User superAdmin;
    private User adminClient;
    private String nomA;
    private String nomB;

    @BeforeEach
    void setUp() {
        Role roleSuper = roleRepository.findByName(ERole.ROLE_SUPER_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_SUPER_ADMIN)));
        Role roleAdmin = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));

        long n = System.nanoTime();
        nomA = "Client Alpha " + n;
        nomB = "Client Beta " + n;

        plateforme = etablissementRepository.save(Etablissement.builder()
                .nom("SIGEP Plateforme").slug("plateforme").build());
        clientA = etablissementRepository.save(Etablissement.builder()
                .nom(nomA).slug("plt-a-" + n).build());
        clientB = etablissementRepository.save(Etablissement.builder()
                .nom(nomB).slug("plt-b-" + n)
                .dateExpiration(LocalDate.now().minusDays(3)) // expiré
                .build());

        superAdmin = userRepository.save(User.builder()
                .email("root-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleSuper)))
                .etablissement(plateforme).build());
        adminClient = userRepository.save(User.builder()
                .email("adm-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin)))
                .etablissement(clientA).build());
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteById(superAdmin.getId());
        userRepository.deleteById(adminClient.getId());
        etablissementRepository.deleteById(clientA.getId());
        etablissementRepository.deleteById(clientB.getId());
        etablissementRepository.deleteById(plateforme.getId());
    }

    @Test
    void superAdmin_voitTousLesEtablissementsClients_maisPasLaPlateforme() throws Exception {
        mockMvc.perform(get("/plateforme").with(user(superAdmin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(nomA)))
                .andExpect(content().string(containsString(nomB)));
    }

    @Test
    void adminEtablissement_nAccedePasALaPlateforme() throws Exception {
        mockMvc.perform(get("/plateforme").with(user(adminClient)))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdmin_nAccedePasALAdminEtablissement() throws Exception {
        mockMvc.perform(get("/admin/dashboard").with(user(superAdmin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonyme_estRedirigeVersLaConnexion() throws Exception {
        mockMvc.perform(get("/plateforme"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/admin-login"));
    }

    @Test
    void superAdmin_prolongeUnAbonnementExpire() throws Exception {
        mockMvc.perform(post("/plateforme/abonnements/prolonger")
                        .with(user(superAdmin)).with(csrf())
                        .param("slug", clientB.getSlug()).param("mois", "3"))
                .andExpect(status().is3xxRedirection());

        LocalDate nouvelleExpiration = etablissementRepository.findBySlug(clientB.getSlug())
                .orElseThrow().getDateExpiration();
        assertThat(nouvelleExpiration).isAfter(LocalDate.now());
    }

    @Test
    void adminEtablissement_neProlongePas() throws Exception {
        mockMvc.perform(post("/plateforme/abonnements/prolonger")
                        .with(user(adminClient)).with(csrf())
                        .param("slug", clientA.getSlug()).param("mois", "1"))
                .andExpect(status().isForbidden());
    }
}
