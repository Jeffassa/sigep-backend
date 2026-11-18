package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.service.JetonEcranSalle;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * L'écran de salle est autorisé par un cookie d'appareil : la clé de l'écran ne figure plus dans
 * l'adresse de la page, où elle restait lisible dans la barre d'adresse et l'historique.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EcranSalleCookieTest {

    @MockitoBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Etablissement ecole;
    private Etablissement autre;
    private User admin;

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        ecole = etablissementRepository.save(Etablissement.builder().nom("Ecole").slug("ecran-" + n)
                .plan(Plan.PRO).kioskKey("cle-ecran-" + n).build());
        autre = etablissementRepository.save(Etablissement.builder().nom("Autre").slug("autre-" + n)
                .plan(Plan.PRO).kioskKey("cle-autre-" + n).build());
        Role role = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        admin = userRepository.save(User.builder().email("ecran-" + n + "@test.local")
                .password(passwordEncoder.encode("Secret@2026"))
                .roles(new HashSet<>(Set.of(role))).etablissement(ecole).build());
    }

    private Cookie ouvrirDepuisLAdmin() throws Exception {
        MvcResult r = mockMvc.perform(get("/admin/ecran-emargement/ouvrir").with(user(admin)))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = r.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).isEqualTo("/api/qr/display?etab=" + ecole.getSlug());
        assertThat(location).doesNotContain(ecole.getKioskKey());
        Cookie c = r.getResponse().getCookie(JetonEcranSalle.COOKIE);
        assertThat(c).isNotNull();
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getPath()).isEqualTo("/api/qr/display");
        assertThat(c.getValue()).doesNotContain(ecole.getKioskKey());
        return new Cookie(c.getName(), c.getValue());
    }

    @Test
    void lEcranOuvertDepuisLAdmin_afficheLeQr_sansCleDansLAdresse() throws Exception {
        Cookie autorisation = ouvrirDepuisLAdmin();

        mockMvc.perform(get("/api/qr/display").param("etab", ecole.getSlug()).cookie(autorisation))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data:image/png;base64,")));
    }

    @Test
    void sansCookie_ouAvecUnCookieFabrique_lEcranEstRefuse() throws Exception {
        mockMvc.perform(get("/api/qr/display").param("etab", ecole.getSlug()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/qr/display").param("etab", ecole.getSlug())
                        .cookie(new Cookie(JetonEcranSalle.COOKIE, "fabrique")))
                .andExpect(status().isForbidden());
    }

    @Test
    void leCookieDUnEtablissement_nOuvrePasLEcranDUnAutre() throws Exception {
        Cookie autorisation = ouvrirDepuisLAdmin();

        mockMvc.perform(get("/api/qr/display").param("etab", autre.getSlug()).cookie(autorisation))
                .andExpect(status().isForbidden());
    }

    @Test
    void regenererLaCle_desactiveLesEcransDejaInstalles() throws Exception {
        Cookie autorisation = ouvrirDepuisLAdmin();

        mockMvc.perform(post("/admin/ecran-emargement/regenerer-cle").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/api/qr/display").param("etab", ecole.getSlug()).cookie(autorisation))
                .andExpect(status().isForbidden());
    }

    @Test
    void unAncienLienAvecCle_poseLeCookie_puisRetireLaCleDeLAdresse() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/qr/display").param("etab", ecole.getSlug())
                        .param("key", ecole.getKioskKey()))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/qr/display?etab=" + ecole.getSlug()))
                .andReturn();
        Cookie c = r.getResponse().getCookie(JetonEcranSalle.COOKIE);
        assertThat(c).isNotNull();

        mockMvc.perform(get("/api/qr/display").param("etab", ecole.getSlug())
                        .cookie(new Cookie(c.getName(), c.getValue())))
                .andExpect(status().isOk());
    }

    @Test
    void uneMauvaiseCle_estRefusee_etNePoseRien() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/qr/display").param("etab", ecole.getSlug()).param("key", "mauvaise"))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(r.getResponse().getCookie(JetonEcranSalle.COOKIE)).isNull();
    }
}
