package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Onboarding SaaS : inscription self-service d'un établissement + son admin. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OnboardingIntegrationTest {

    // Mocké pour ne pas commiter l'admin/établissement par défaut dans la base H2
    // partagée entre contextes de test (sinon collision avec d'autres tests).
    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @BeforeEach
    void setUp() {
        // DataInitializer mocké → on garantit l'existence du rôle ADMIN requis par l'onboarding.
        roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
    }

    private String body(String nom, String email) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "nomEtablissement", nom,
                "adminEmail", email,
                "adminPassword", "Secret@2026",
                "adminNom", "Diallo", "adminPrenom", "Awa"));
    }

    @Test
    void inscription_cree_le_tenant_EN_ATTENTE_sans_token() throws Exception {
        // SA-2 : le dossier doit être validé par le super admin avant toute connexion —
        // l'inscription ne délivre donc AUCUN token et l'établissement naît EN_ATTENTE.
        mockMvc.perform(post("/api/saas/etablissements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Université Atlantique", "admin@atlantique.ci")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.roles[0]").value("ROLE_ADMIN"));

        var etab = etablissementRepository.findBySlug("universite-atlantique").orElseThrow();
        assertThat(etab.getStatut()).isEqualTo(ci.esatic.sigep.entity.StatutEtablissement.EN_ATTENTE);
        User admin = userRepository.findByEmail("admin@atlantique.ci").orElseThrow();
        assertThat(admin.getEtablissement()).isNotNull();
        assertThat(admin.getEtablissement().getNom()).isEqualTo("Université Atlantique");
    }

    @Test
    void email_admin_en_double_est_refuse() throws Exception {
        mockMvc.perform(post("/api/saas/etablissements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("École A", "dup@ecole.ci")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/saas/etablissements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("École B", "dup@ecole.ci")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void slug_genere_unique_pour_deux_noms_identiques() throws Exception {
        mockMvc.perform(post("/api/saas/etablissements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Grande École", "a@ge.ci")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/saas/etablissements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Grande École", "b@ge.ci")))
                .andExpect(status().isOk());

        assertThat(etablissementRepository.findBySlug("grande-ecole")).isPresent();
        assertThat(etablissementRepository.findBySlug("grande-ecole-2")).isPresent();
    }
}
