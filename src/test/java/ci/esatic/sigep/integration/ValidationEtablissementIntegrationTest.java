package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.StatutEtablissement;
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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SA-2 : un établissement inscrit doit être VALIDÉ par le super admin avant de pouvoir
 * se connecter. Tant que le dossier est EN_ATTENTE (ou REFUSÉ), la connexion est bloquée
 * avec un message explicite. La validation est réservée au rôle SUPER_ADMIN.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ValidationEtablissementIntegrationTest {

    // Mocké : évite de commiter l'admin/établissement par défaut dans la base H2 partagée.
    @MockBean
    private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private UserRepository userRepository;

    private User superAdmin;
    private User adminClient;

    @BeforeEach
    void setUp() {
        Role roleAdmin = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        Role roleSuper = roleRepository.findByName(ERole.ROLE_SUPER_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_SUPER_ADMIN)));

        long n = System.nanoTime();
        Etablissement plateforme = etablissementRepository.save(Etablissement.builder()
                .nom("Plateforme").slug("val-plt-" + n).build());
        Etablissement clientValide = etablissementRepository.save(Etablissement.builder()
                .nom("Client Valide").slug("val-cli-" + n).build());

        superAdmin = userRepository.save(User.builder()
                .email("val-root-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleSuper))).etablissement(plateforme).build());
        adminClient = userRepository.save(User.builder()
                .email("val-adm-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin))).etablissement(clientValide).build());
    }

    private String inscrire(String nom, String email) throws Exception {
        mockMvc.perform(post("/api/saas/etablissements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "nomEtablissement", nom, "adminEmail", email,
                                "adminPassword", "Secret@2026", "adminNom", "N", "adminPrenom", "P"))))
                .andExpect(status().isOk());
        return etablissementRepository.findAll().stream()
                .filter(e -> nom.equals(e.getNom()))
                .findFirst().orElseThrow().getSlug();
    }

    private org.springframework.test.web.servlet.ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("email", email, "password", "Secret@2026"))));
    }

    @Test
    void etablissement_en_attente_ne_peut_pas_se_connecter_puis_valide_peut() throws Exception {
        long n = System.nanoTime();
        String email = "dir-" + n + "@ecole.test";
        String slug = inscrire("Ecole Workflow " + n, email);

        // 1) Dossier EN_ATTENTE : connexion bloquée avec le message d'analyse.
        login(email).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("en cours d'analyse")));

        // 2) Le super admin valide le dossier.
        mockMvc.perform(post("/plateforme/etablissements/valider")
                        .with(user(superAdmin)).with(csrf())
                        .param("slug", slug))
                .andExpect(status().is3xxRedirection());
        assertThat(etablissementRepository.findBySlug(slug).orElseThrow().getStatut())
                .isEqualTo(StatutEtablissement.VALIDE);

        // 3) La connexion aboutit désormais (token délivré).
        login(email).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void etablissement_refuse_reste_bloque() throws Exception {
        long n = System.nanoTime();
        String email = "ref-" + n + "@ecole.test";
        String slug = inscrire("Ecole Refusee " + n, email);

        mockMvc.perform(post("/plateforme/etablissements/refuser")
                        .with(user(superAdmin)).with(csrf())
                        .param("slug", slug))
                .andExpect(status().is3xxRedirection());

        login(email).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("retenu")));
    }

    @Test
    void adminEtablissement_ne_peut_pas_valider_un_dossier() throws Exception {
        long n = System.nanoTime();
        String slug = inscrire("Ecole Intrusion " + n, "int-" + n + "@ecole.test");

        mockMvc.perform(post("/plateforme/etablissements/valider")
                        .with(user(adminClient)).with(csrf())
                        .param("slug", slug))
                .andExpect(status().isForbidden());
    }
}
