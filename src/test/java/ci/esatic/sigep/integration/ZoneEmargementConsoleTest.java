package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** La zone d'émargement se règle depuis la page de l'écran ; seule cette page peut localiser. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ZoneEmargementConsoleTest {

    @MockitoBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;

    private Etablissement ecole;
    private User admin;

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        ecole = etablissementRepository.save(Etablissement.builder().nom("Ecole").slug("zone-" + n)
                .plan(Plan.PRO).kioskKey("cle-zone-" + n).build());
        Role role = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        admin = userRepository.save(User.builder().email("zone-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(role))).etablissement(ecole).build());
    }

    @Test
    void laZoneSEnregistre_puisSeRetire() throws Exception {
        mockMvc.perform(post("/admin/ecran-emargement/zone").with(user(admin)).with(csrf())
                        .param("coordonnees", "5.359950, -4.008260").param("rayon", "200"))
                .andExpect(redirectedUrl("/admin/ecran-emargement?zone=1#zone"));
        Etablissement apres = etablissementRepository.findById(ecole.getId()).orElseThrow();
        assertThat(apres.isZoneDefinie()).isTrue();
        assertThat(apres.getZoneLatitude()).isEqualTo(5.35995);
        assertThat(apres.getZoneLongitude()).isEqualTo(-4.00826);
        assertThat(apres.getZoneRayonMetres()).isEqualTo(200);

        mockMvc.perform(get("/admin/ecran-emargement").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("5.359950, -4.008260")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Retirer la zone")));

        mockMvc.perform(post("/admin/ecran-emargement/zone").with(user(admin)).with(csrf()).param("retirer", "1"))
                .andExpect(redirectedUrl("/admin/ecran-emargement?zoneRetiree=1#zone"));
        assertThat(etablissementRepository.findById(ecole.getId()).orElseThrow().isZoneDefinie()).isFalse();
    }

    @Test
    void desCoordonneesOuUnRayonIllisibles_nEnregistrentRien() throws Exception {
        mockMvc.perform(post("/admin/ecran-emargement/zone").with(user(admin)).with(csrf())
                        .param("coordonnees", "Abidjan").param("rayon", "300"))
                .andExpect(redirectedUrl("/admin/ecran-emargement#zone"))
                .andExpect(flash().attribute("zoneInvalide", true));
        mockMvc.perform(post("/admin/ecran-emargement/zone").with(user(admin)).with(csrf())
                        .param("coordonnees", "5.36, -4.01").param("rayon", "50000"))
                .andExpect(redirectedUrl("/admin/ecran-emargement#zone"));
        assertThat(etablissementRepository.findById(ecole.getId()).orElseThrow().isZoneDefinie()).isFalse();
    }

    @Test
    void seuleLaPageDeReglage_peutDemanderLaPosition() throws Exception {
        mockMvc.perform(get("/admin/ecran-emargement").with(user(admin)))
                .andExpect(header().string("Permissions-Policy", org.hamcrest.Matchers.startsWith("geolocation=(self)")));
        mockMvc.perform(get("/admin-login"))
                .andExpect(header().string("Permissions-Policy", org.hamcrest.Matchers.startsWith("geolocation=()")));
    }
}
