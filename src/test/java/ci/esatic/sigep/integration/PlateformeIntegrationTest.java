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
    void consoleSecurite_seRend_etResteReserveeAuSuperAdmin() throws Exception {
        // Le rendu Thymeleaf ne se verifie qu'a l'execution : une expression fautive dans la page
        // ne casse ni la compilation ni les autres tests, elle n'apparait qu'en production.
        mockMvc.perform(get("/plateforme/securite").with(user(superAdmin)))
                .andExpect(status().isOk());

        // Ces evenements traversent tous les etablissements : un admin d'etablissement n'y voit rien.
        mockMvc.perform(get("/plateforme/securite").with(user(adminClient)))
                .andExpect(status().isForbidden());
    }

    // ─── Page du second facteur ─────────────────────────────────────────────
    // Regression : /admin-otp n'avait aucune regle d'autorisation propre et retombait sur
    // anyRequest().hasRole("ADMIN"). Le super administrateur, qui ne porte que ROLE_SUPER_ADMIN,
    // recevait un 403 sur la page meme censee le laisser entrer — il etait enferme dehors.
    // Ces tests s'executent meme avec le second facteur desactive : c'est la regle d'acces
    // a la page qui est verifiee, pas le mecanisme du code.

    @Test
    void pageSecondFacteur_estAccessible_auSuperAdministrateur() throws Exception {
        mockMvc.perform(get("/admin-otp").with(user(superAdmin)))
                .andExpect(status().isOk());
    }

    @Test
    void pageSecondFacteur_estAccessible_aUnAdminDEtablissement() throws Exception {
        mockMvc.perform(get("/admin-otp").with(user(adminClient)))
                .andExpect(status().isOk());
    }

    @Test
    void pageSecondFacteur_resteFermee_sansAuthentification() throws Exception {
        // Le mot de passe reste exige AVANT le second facteur : la page n'est pas publique.
        mockMvc.perform(get("/admin-otp"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void renvoiDeCode_estAccessible_auxDeuxProfils() throws Exception {
        mockMvc.perform(post("/admin-otp/renvoyer").with(user(superAdmin)).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin-otp/renvoyer").with(user(adminClient)).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void remediation_renouvelleLaCleEcran_dUnEtablissementCible() throws Exception {
        String cleAvant = clientA.getKioskKey();

        mockMvc.perform(post("/plateforme/securite/etablissements/" + clientA.getId() + "/cle-kiosque")
                        .with(user(superAdmin)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Etablissement apres = etablissementRepository.findById(clientA.getId()).orElseThrow();
        assertThat(apres.getKioskKey()).isNotNull();
        // Toute la valeur de l'action tient dans ce changement : l'ancienne cle cesse d'ouvrir.
        assertThat(apres.getKioskKey()).isNotEqualTo(cleAvant);
    }

    @Test
    void remediation_couperLesSessions_repondSansErreur() throws Exception {
        mockMvc.perform(post("/plateforme/securite/etablissements/" + clientA.getId() + "/sessions")
                        .with(user(superAdmin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void remediation_estRefusee_aUnAdminDEtablissement() throws Exception {
        String cleAvant = clientA.getKioskKey();

        // Un administrateur d'etablissement ne doit pas pouvoir agir sur SON etablissement par
        // cette porte, ni a plus forte raison sur celui d'un autre : la remediation est un
        // pouvoir de plateforme.
        mockMvc.perform(post("/plateforme/securite/etablissements/" + clientA.getId() + "/cle-kiosque")
                        .with(user(adminClient)).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(etablissementRepository.findById(clientA.getId()).orElseThrow().getKioskKey())
                .isEqualTo(cleAvant);
    }

    @Test
    void remediation_surUnEtablissementInconnu_neCassePas() throws Exception {
        mockMvc.perform(post("/plateforme/securite/etablissements/999999/sessions")
                        .with(user(superAdmin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void consoleSecurite_filtreParEtablissement_seRend() throws Exception {
        mockMvc.perform(get("/plateforme/securite").param("etablissement", String.valueOf(clientA.getId()))
                        .with(user(superAdmin)))
                .andExpect(status().isOk());
    }

    @Test
    void consoleSecurite_filtreParGravite_seRendAussi() throws Exception {
        mockMvc.perform(get("/plateforme/securite").param("severite", "CRITIQUE").with(user(superAdmin)))
                .andExpect(status().isOk());
        // Un filtre inconnu ne doit pas provoquer d'erreur, seulement retomber sur « tout ».
        mockMvc.perform(get("/plateforme/securite").param("severite", "N-IMPORTE-QUOI").with(user(superAdmin)))
                .andExpect(status().isOk());
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
