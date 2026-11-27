package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.controller.web.AdminOtpController;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Abonnement expiré : l'administrateur arrive sur la page d'abonnement dès sa connexion, et la
 * console ne lui propose plus que l'abonnement et la déconnexion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccesSuspenduConsoleTest {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Etablissement expire;
    private Etablissement aJour;
    private User adminExpire;
    private User adminAJour;

    @BeforeEach
    void setUp() {
        Role roleAdmin = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        long n = System.nanoTime();
        expire = etablissementRepository.save(Etablissement.builder()
                .nom("Expiré " + n).slug("exp-" + n).dateExpiration(LocalDate.now().minusDays(10)).build());
        aJour = etablissementRepository.save(Etablissement.builder()
                .nom("À jour " + n).slug("ajour-" + n).dateExpiration(LocalDate.now().plusMonths(2)).build());
        adminExpire = userRepository.save(User.builder()
                .email("adm-exp-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin))).etablissement(expire).build());
        adminAJour = userRepository.save(User.builder()
                .email("adm-ajour-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin))).etablissement(aJour).build());
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteById(adminExpire.getId());
        userRepository.deleteById(adminAJour.getId());
        etablissementRepository.deleteById(expire.getId());
        etablissementRepository.deleteById(aJour.getId());
    }

    private MockHttpSession sessionVerifiee() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(AdminOtpController.VERIFIED, true);
        return s;
    }

    @Test
    void apresLeCode_unEtablissementExpire_arriveSurLAbonnement() throws Exception {
        mockMvc.perform(get("/admin-otp").with(user(adminExpire)).session(sessionVerifiee()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/abonnement"));
        mockMvc.perform(get("/admin-otp").with(user(adminAJour)).session(sessionVerifiee()))
                .andExpect(redirectedUrl("/admin/dashboard"));
    }

    @Test
    void consoleSuspendue_neProposeQueLAbonnementEtLaDeconnexion() throws Exception {
        mockMvc.perform(get("/admin/abonnement").with(user(adminExpire)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-label=\"Abonnement\"")))
                .andExpect(content().string(containsString("data-label=\"Déconnexion\"")))
                .andExpect(content().string(not(containsString("data-label=\"Enseignants\""))))
                .andExpect(content().string(not(containsString("data-label=\"Tableau de bord\""))))
                .andExpect(content().string(not(containsString("href=\"/admin/statistiques\""))));
        mockMvc.perform(get("/admin/enseignants").with(user(adminExpire)))
                .andExpect(redirectedUrl("/admin/abonnement"));
    }

    @Test
    void consoleAJour_garderTousSesOnglets() throws Exception {
        mockMvc.perform(get("/admin/abonnement").with(user(adminAJour)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-label=\"Tableau de bord\"")))
                .andExpect(content().string(containsString("data-label=\"Enseignants\"")));
    }
}
