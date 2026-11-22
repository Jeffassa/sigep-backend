package ci.esatic.sigep.annuaire;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.StatutReconnaissance;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Listes officielles : suggestions à l'inscription, rapprochement d'un établissement qui s'inscrit,
 * et décision de la plateforme pour ceux qui restent à vérifier.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReconnaissanceIntegrationTest {

    @MockitoBean private DataInitializer dataInitializer;
    @MockitoBean private ci.esatic.sigep.service.MailService mailService;

    @Autowired private MockMvc mockMvc;
    @Autowired private EtablissementOfficielRepository officiels;
    @Autowired private ReconnaissanceService reconnaissance;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private final List<String> emails = new ArrayList<>();

    @AfterEach
    void nettoyer() {
        // TenantIsolationTest compte les établissements actifs : on retire ceux créés ici.
        for (String email : emails) {
            userRepository.findAll().stream().filter(u -> u.getEmail().equals(email)).forEach(u -> {
                Etablissement e = u.getEtablissement();
                userRepository.delete(u);
                if (e != null) etablissementRepository.deleteById(e.getId());
            });
        }
        officiels.deleteAll();
    }

    private void officiel(SourceOfficielle source, CategorieOfficielle categorie, String code, String nom) {
        officiels.save(EtablissementOfficiel.de(source, categorie, code, nom, "ABIDJAN", "COCODY", LocalDateTime.now()));
    }

    private Etablissement inscrire(String nom) throws Exception {
        roleRepository.findByName(ERole.ROLE_ADMIN).orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        String email = "reco-" + System.nanoTime() + "@ecole.ci";
        emails.add(email);
        mockMvc.perform(post("/api/saas/etablissements").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nomEtablissement\":\"" + nom + "\",\"adminEmail\":\"" + email
                                + "\",\"adminPassword\":\"Kora#Lagune7\"}"))
                .andExpect(status().isOk());
        return etablissementRepository.findAll().stream().filter(e -> e.getNom().equals(nom))
                .max(java.util.Comparator.comparing(Etablissement::getId)).orElseThrow();
    }

    @Autowired private ListesOfficiellesService listes;

    @Test
    void leClassementDuBepc_leplusRecentEstTrouveSeul() throws Exception {
        int annee = java.time.Year.now().getValue();
        com.sun.net.httpserver.HttpServer serveur = com.sun.net.httpserver.HttpServer
                .create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        // Publiés : la session d'il y a deux ans dans un dossier, celle de l'an dernier dans l'autre.
        for (String chemin : List.of("/public/docs/BEPC_SESSION_" + (annee - 2) + "_CLASSEMENT_DES_ETABLISSEMENTS.pdf",
                "/public/data/json/BEPC_SESSION_" + (annee - 1) + "_CLASSEMENT_DES_ETABLISSEMENTS.pdf")) {
            serveur.createContext(chemin, ex -> {
                ex.getResponseHeaders().add("Content-Type", "application/pdf");
                ex.sendResponseHeaders(200, -1);
                ex.close();
            });
        }
        serveur.start();
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(listes, "menaSite",
                    "http://127.0.0.1:" + serveur.getAddress().getPort());
            assertThat(listes.trouverClassementMena())
                    .endsWith("/public/data/json/BEPC_SESSION_" + (annee - 1) + "_CLASSEMENT_DES_ETABLISSEMENTS.pdf");
        } finally {
            serveur.stop(0);
            org.springframework.test.util.ReflectionTestUtils.setField(listes, "menaSite", "https://statistiques.men-deco.org");
        }
    }

    @Test
    void lesSuggestions_proposentDAbordLesListesOfficielles() throws Exception {
        officiel(SourceOfficielle.MESRS_GRANDES_ECOLES, CategorieOfficielle.GRANDE_ECOLE_PRIVEE, null,
                "ABIDJAN BUSINESS SCHOOL -(ABS)");
        mockMvc.perform(get("/api/saas/annuaire").param("q", "business school"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nom").value("ABIDJAN BUSINESS SCHOOL -(ABS)"))
                .andExpect(jsonPath("$[0].type").value("Grande école privée"));
    }

    @Test
    void unNomDeLaListe_estReconnu_avecSonCode() throws Exception {
        officiel(SourceOfficielle.MENA_BEPC, CategorieOfficielle.SECONDAIRE_PUBLIC, "000405", "LYCEE SAINTE MARIE ABIDJAN");
        officiel(SourceOfficielle.MESRS_GRANDES_ECOLES, CategorieOfficielle.GRANDE_ECOLE_PRIVEE, null,
                "ABIDJAN BUSINESS SCHOOL -(ABS)");

        Etablissement lycee = inscrire("Lycée Sainte-Marie Abidjan");     // accents, casse, tiret : même clé
        assertThat(lycee.getReconnaissance()).isEqualTo(StatutReconnaissance.RECONNU);
        assertThat(lycee.getOfficielCode()).isEqualTo("000405");
        assertThat(lycee.getOfficielSource()).isEqualTo("MENA_BEPC");

        Etablissement ecole = inscrire("Abidjan Business School");        // sans le sigle
        assertThat(ecole.getReconnaissance()).isEqualTo(StatutReconnaissance.RECONNU);
    }

    @Test
    void uneFicheOfficielle_neReconnaitQuUnSeulClient() throws Exception {
        officiel(SourceOfficielle.MENA_BEPC, CategorieOfficielle.SECONDAIRE_PUBLIC, "000777", "LYCEE MODERNE DE TEST");

        Etablissement premier = inscrire("Lycée moderne de test");
        assertThat(premier.getReconnaissance()).isEqualTo(StatutReconnaissance.RECONNU);

        // Un second client qui réclame la même fiche reste à vérifier, fiche notée.
        Etablissement second = inscrire("Lycée Moderne de Test");
        assertThat(second.getReconnaissance()).isEqualTo(StatutReconnaissance.A_VERIFIER);
        assertThat(second.getOfficielCode()).isEqualTo("000777");

        // Un nouveau rapprochement garde la fiche au premier inscrit.
        reconnaissance.rapprocherTous();
        assertThat(etablissementRepository.findById(premier.getId()).orElseThrow().getReconnaissance())
                .isEqualTo(StatutReconnaissance.RECONNU);
        assertThat(etablissementRepository.findById(second.getId()).orElseThrow().getReconnaissance())
                .isEqualTo(StatutReconnaissance.A_VERIFIER);
    }

    @Test
    void unNomInconnuOuAmbigu_resteAVerifier_etLaPlateformeTranche() throws Exception {
        officiel(SourceOfficielle.MENA_BEPC, CategorieOfficielle.SECONDAIRE_PRIVE, "111111", "COLLEGE SAINT JOSEPH");
        officiel(SourceOfficielle.MENA_BEPC, CategorieOfficielle.SECONDAIRE_PRIVE, "222222", "COLLEGE SAINT JOSEPH");

        Etablissement ambigu = inscrire("Collège Saint Joseph");
        assertThat(ambigu.getReconnaissance()).isEqualTo(StatutReconnaissance.A_VERIFIER);
        Etablissement inconnu = inscrire("Institut Imaginaire " + System.nanoTime());
        assertThat(inconnu.getReconnaissance()).isEqualTo(StatutReconnaissance.A_VERIFIER);

        Role roleSuper = roleRepository.findByName(ERole.ROLE_SUPER_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_SUPER_ADMIN)));
        User root = User.builder().id(-1L).email("root-reco@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleSuper))).build();

        mockMvc.perform(get("/plateforme/annuaire").with(user(root)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Clients à vérifier")))
                .andExpect(content().string(containsString(inconnu.getNom())));

        // Sans constat, rien n'est enregistré.
        mockMvc.perform(post("/plateforme/annuaire/reconnaissance/{id}", ambigu.getId()).with(user(root)).with(csrf())
                        .param("decision", "VERIFIE").param("constat", ""))
                .andExpect(status().is3xxRedirection());
        assertThat(etablissementRepository.findById(ambigu.getId()).orElseThrow().getReconnaissance())
                .isEqualTo(StatutReconnaissance.A_VERIFIER);

        mockMvc.perform(post("/plateforme/annuaire/reconnaissance/{id}", ambigu.getId()).with(user(root)).with(csrf())
                        .param("decision", "VERIFIE").param("constat", "Arrêté d'ouverture reçu, code 111111."))
                .andExpect(status().is3xxRedirection());
        Etablissement tranche = etablissementRepository.findById(ambigu.getId()).orElseThrow();
        assertThat(tranche.getReconnaissance()).isEqualTo(StatutReconnaissance.VERIFIE);
        assertThat(tranche.getReconnaissanceAuteur()).isEqualTo("root-reco@test.local");

        // Un nouvel import ne revient jamais sur une décision de la plateforme.
        officiels.deleteAll();
        reconnaissance.rapprocherTous();
        assertThat(etablissementRepository.findById(ambigu.getId()).orElseThrow().getReconnaissance())
                .isEqualTo(StatutReconnaissance.VERIFIE);
    }
}
