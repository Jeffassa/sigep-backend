package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test d'intégration de l'interface web admin : vérifie le routage de sécurité
 * (redirections, contrôle d'accès) ET le rendu réel des templates Thymeleaf refondus
 * (fragments head/topbar/bottomnav, expressions). Un template invalide ferait échouer ces tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminWebIntegrationTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EnseignantRepository enseignantRepository;

    @BeforeEach
    void setUp() {
        Role role = roleRepository.findByName(ERole.ROLE_ENSEIGNANT)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ENSEIGNANT)));
        User u = userRepository.save(User.builder()
                .email("prof.web@esatic.ci").password(passwordEncoder.encode("x"))
                .roles(Set.of(role)).build());
        enseignantRepository.save(Enseignant.builder()
                .matricule("ENS-WEB-1").nom("Traore").prenom("Sira")
                .departement("Informatique").grade("Assistant")
                .statut(StatutEnseignant.PENDING).user(u).build());
    }

    // ─── Routage / sécurité ───────────────────────────────────────────────────

    @Test
    void racine_devraitRedirigerVersDashboard() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/dashboard"));
    }

    @Test
    void dashboard_sansAuth_devraitRedirigerVersLogin() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/admin-login"));
    }

    @Test
    void loginAdmin_devraitAfficherPagePersonnalisee() throws Exception {
        mockMvc.perform(get("/admin-login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SIGEP")));
    }

    // ─── Rendu des templates Thymeleaf (avec admin authentifié) ─────────────────

    @Test
    @WithMockUser(username = "admin@esatic.ci", roles = "ADMIN")
    void dashboard_devraitSeRendreCorrectement() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Tableau de bord")));
    }

    @Test
    @WithMockUser(username = "admin@esatic.ci", roles = "ADMIN")
    void enseignants_devraitSeRendreCorrectement() throws Exception {
        mockMvc.perform(get("/admin/enseignants"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Enseignants")));
    }

    @Test
    @WithMockUser(username = "admin@esatic.ci", roles = "ADMIN")
    void rapports_devraitSeRendreCorrectement() throws Exception {
        mockMvc.perform(get("/admin/rapports"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin@esatic.ci", roles = "ADMIN")
    void alertes_devraitSeRendreCorrectement() throws Exception {
        mockMvc.perform(get("/admin/alertes"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Alertes")));
    }
}
