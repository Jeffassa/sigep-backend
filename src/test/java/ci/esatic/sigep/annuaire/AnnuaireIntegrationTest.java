package ci.esatic.sigep.annuaire;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Annuaire UEMOA : collecte d'une extraction (servie ici par un faux serveur local), recherche
 * publique à l'inscription, pays de l'établissement et page de la plateforme.
 * Pas @Transactional : la collecte écrit hors de toute transaction de test ; chaque test nettoie.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnuaireIntegrationTest {

    @MockitoBean private DataInitializer dataInitializer;
    @MockitoBean private ci.esatic.sigep.service.MailService mailService;

    @Autowired private MockMvc mockMvc;
    @Autowired private AnnuaireEtablissementRepository annuaire;
    @Autowired private AnnuaireCollecteRepository collectes;
    @Autowired private AnnuaireCollecteService collecte;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private HttpServer serveur;

    @AfterEach
    void nettoyer() {
        if (serveur != null) serveur.stop(0);
        annuaire.deleteAll();
        collectes.deleteAll();
    }

    private AnnuaireEtablissement fiche(String nom, TypeAnnuaire type, String pays, String id) {
        return annuaire.save(AnnuaireEtablissement.builder().nom(nom).nomRecherche(TexteRecherche.normaliser(nom))
                .type(type).ville("Abidjan").paysCode(pays).source("OSM").sourceId(id)
                .dateCollecte(LocalDateTime.now()).build());
    }

    @Test
    void laCollecte_rangeLExtractionDuPays_etGardeLAncienneListeSiLaSourceFlanche() throws Exception {
        byte[][] reponse = {LecteurEducationFacilitiesTest.zip(LecteurEducationFacilitiesTest.GEOJSON)};
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.createContext("/ISO3/CIV/education_facilities/hotosm_civ_education_facilities_osm_geojson.zip", ex -> {
            ex.sendResponseHeaders(200, reponse[0].length);
            ex.getResponseBody().write(reponse[0]);
            ex.close();
        });
        serveur.start();
        ReflectionTestUtils.setField(collecte, "sourceUrl", "http://127.0.0.1:" + serveur.getAddress().getPort());

        collecte.collecterPays(PaysUemoa.CI);
        assertThat(annuaire.countByPaysCode("CI")).isEqualTo(3);
        assertThat(collectes.findById("CI").orElseThrow().getNombre()).isEqualTo(3);
        assertThat(collectes.findById("CI").orElseThrow().getErreur()).isNull();

        // Extraction vide : panne de la source, l'annuaire n'est pas vidé.
        reponse[0] = LecteurEducationFacilitiesTest.zip("{\"type\":\"FeatureCollection\",\"features\":[]}");
        collecte.collecterPays(PaysUemoa.CI);
        assertThat(annuaire.countByPaysCode("CI")).isEqualTo(3);
        assertThat(collectes.findById("CI").orElseThrow().getErreur()).contains("anormalement");
    }

    @Test
    void laRecherche_ignoreLesAccents_etResteEnCoteDIvoire() throws Exception {
        fiche("Lycée Félix Houphouët-Boigny", TypeAnnuaire.LYCEE, "CI", "node/10");
        fiche("Université Félix Houphouët-Boigny", TypeAnnuaire.UNIVERSITE, "CI", "node/11");
        fiche("École Félix Éboué", TypeAnnuaire.ECOLE, "SN", "node/12");

        // Anonyme : la recherche sert le formulaire d'inscription public.
        mockMvc.perform(get("/api/saas/annuaire").param("q", "felix houphouet").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].nom").value("Université Félix Houphouët-Boigny"))   // l'université d'abord
                .andExpect(jsonPath("$[0].paysNom").value("Côte d'Ivoire"));

        // Seule la Côte d'Ivoire est ouverte : un autre pays demandé n'y change rien.
        mockMvc.perform(get("/api/saas/annuaire").param("q", "Félix").param("pays", "SN"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Éboué"))));

        mockMvc.perform(get("/api/saas/annuaire").param("q", "f"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void lInscription_estEnregistreeEnCoteDIvoire() throws Exception {
        roleRepository.findByName(ERole.ROLE_ADMIN).orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        long n = System.nanoTime();
        mockMvc.perform(post("/api/saas/etablissements").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nomEtablissement\":\"École Annuaire " + n + "\",\"adminEmail\":\"annuaire-" + n
                                + "@ecole.sn\",\"adminPassword\":\"Kora#Lagune7\",\"pays\":\"sn\"}"))
                .andExpect(status().isOk());
        Etablissement e = etablissementRepository.findBySlug("ecole-annuaire-" + n).orElseThrow();
        assertThat(e.getPaysCode()).isEqualTo("CI");   // pays fermé : la Côte d'Ivoire est retenue
        // Nettoyage : TenantIsolationTest compte les établissements actifs.
        userRepository.findAll().stream().filter(u -> u.getEmail().equals("annuaire-" + n + "@ecole.sn"))
                .forEach(userRepository::delete);
        etablissementRepository.delete(e);
    }

    @Test
    void laPageDeLaPlateforme_montreLesPaysEtLeursOperateurs() throws Exception {
        fiche("Lycée Classique d'Abidjan", TypeAnnuaire.LYCEE, "CI", "node/20");
        Role roleSuper = roleRepository.findByName(ERole.ROLE_SUPER_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_SUPER_ADMIN)));
        User root = User.builder().id(-1L).email("root-annuaire@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleSuper))).build();

        mockMvc.perform(get("/plateforme/annuaire").with(user(root)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Guinée-Bissau")))
                .andExpect(content().string(containsString("Wave")))
                .andExpect(content().string(containsString("OpenStreetMap")));
    }
}
