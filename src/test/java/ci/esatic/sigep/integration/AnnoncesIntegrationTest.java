package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.Annonce;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.TypeAnnonce;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.AnnonceRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.service.AnnonceService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Annonces de la plateforme : le super administrateur publie, chaque console d'établissement
 * affiche le bandeau, et seul le super administrateur peut publier ou retirer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnoncesIntegrationTest {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private AnnonceRepository annonceRepository;
    @Autowired private AnnonceService annonceService;

    private Etablissement plateforme;
    private Etablissement client;
    private User superAdmin;
    private User adminClient;

    @BeforeEach
    void setUp() {
        annonceRepository.deleteAll();
        annonceService.invalider();
        Role roleSuper = roleRepository.findByName(ERole.ROLE_SUPER_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_SUPER_ADMIN)));
        Role roleAdmin = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        long n = System.nanoTime();
        plateforme = etablissementRepository.save(Etablissement.builder()
                .nom("SIGEP Plateforme").slug("plateforme-ann-" + n).build());
        client = etablissementRepository.save(Etablissement.builder()
                .nom("Client annonces " + n).slug("ann-" + n).build());
        superAdmin = userRepository.save(User.builder()
                .email("root-ann-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleSuper))).etablissement(plateforme).build());
        adminClient = userRepository.save(User.builder()
                .email("adm-ann-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin))).etablissement(client).build());
    }

    @AfterEach
    void tearDown() {
        annonceRepository.deleteAll();
        annonceService.invalider();
        userRepository.deleteById(superAdmin.getId());
        userRepository.deleteById(adminClient.getId());
        etablissementRepository.deleteById(client.getId());
        etablissementRepository.deleteById(plateforme.getId());
    }

    private void publier(String type, String message) throws Exception {
        mockMvc.perform(post("/plateforme/annonces").with(user(superAdmin)).with(csrf())
                        .param("type", type).param("message", message))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("ok"));
    }

    @Test
    void uneAnnoncePubliee_apparaitDansLaConsoleDeLEtablissement() throws Exception {
        publier("NOUVEAUTE", "Les rapports PDF sont disponibles.");

        mockMvc.perform(get("/admin/dashboard").with(user(adminClient)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("annonce-plateforme")))
                .andExpect(content().string(containsString("Les rapports PDF sont disponibles.")));
        mockMvc.perform(get("/plateforme/annonces").with(user(superAdmin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Les rapports PDF sont disponibles.")))
                .andExpect(content().string(containsString("En cours")));
    }

    @Test
    void leMessage_estAfficheCommeDuTexte_jamaisCommeDuCode() throws Exception {
        publier("INFORMATION", "<script>alert(1)</script>");

        mockMvc.perform(get("/admin/dashboard").with(user(adminClient)))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(1)"))));
    }

    @Test
    void uneAnnonceMasquee_neRevientPas_maisUneMaintenanceReste() throws Exception {
        publier("INFORMATION", "Information masquable.");
        Long id = annonceRepository.findAll().get(0).getId();

        mockMvc.perform(get("/admin/dashboard").with(user(adminClient))
                        .cookie(new Cookie("sigep_annonce_lue", String.valueOf(id))))
                .andExpect(content().string(not(containsString("Information masquable."))));

        publier("MAINTENANCE", "Coupure ce soir de 22 h à 23 h.");
        Long idMaintenance = annonceRepository.findAll().stream()
                .filter(a -> a.getType() == TypeAnnonce.MAINTENANCE).findFirst().orElseThrow().getId();
        mockMvc.perform(get("/admin/dashboard").with(user(adminClient))
                        .cookie(new Cookie("sigep_annonce_lue", String.valueOf(idMaintenance))))
                .andExpect(content().string(containsString("Coupure ce soir de 22 h à 23 h.")))
                .andExpect(content().string(not(containsString("annonce-fermer"))));
    }

    @Test
    void uneAnnonceRetiree_disparaitDesConsoles() throws Exception {
        publier("INFORMATION", "Bientôt retirée.");
        Long id = annonceRepository.findAll().get(0).getId();

        mockMvc.perform(post("/plateforme/annonces/" + id + "/clore").with(user(superAdmin)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/admin/dashboard").with(user(adminClient)))
                .andExpect(content().string(not(containsString("Bientôt retirée."))));
        assertThat(annonceRepository.findById(id).orElseThrow().getFin()).isNotNull();
    }

    @Test
    void uneAnnonceProgrammee_nApparaitPasAvantSonDebut() {
        annonceService.publier(TypeAnnonce.MAINTENANCE, "Plus tard.",
                LocalDateTime.now().plusHours(2), null, superAdmin.getEmail());

        assertThat(annonceService.enCours()).isEmpty();
    }

    @Test
    void unMessageVide_ouUneFinPassee_sontRefuses() throws Exception {
        mockMvc.perform(post("/plateforme/annonces").with(user(superAdmin)).with(csrf())
                        .param("type", "INFORMATION").param("message", "   "))
                .andExpect(flash().attributeExists("erreur"));
        mockMvc.perform(post("/plateforme/annonces").with(user(superAdmin)).with(csrf())
                        .param("type", "INFORMATION").param("message", "Trop tard")
                        .param("debut", "2020-01-01T10:00").param("fin", "2020-01-02T10:00"))
                .andExpect(flash().attributeExists("erreur"));

        assertThat(annonceRepository.count()).isZero();
    }

    @Test
    void unAdminDEtablissement_nePeutNiPublierNiRetirer() throws Exception {
        mockMvc.perform(post("/plateforme/annonces").with(user(adminClient)).with(csrf())
                        .param("type", "INFORMATION").param("message", "Usurpation"))
                .andExpect(status().isForbidden());
        Annonce a = annonceService.publier(TypeAnnonce.INFORMATION, "Officielle", null, null, superAdmin.getEmail());
        mockMvc.perform(post("/plateforme/annonces/" + a.getId() + "/clore").with(user(adminClient)).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(annonceRepository.count()).isEqualTo(1);
        assertThat(annonceRepository.findById(a.getId()).orElseThrow().getFin()).isNull();
    }
}
