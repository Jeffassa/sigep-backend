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
    @Autowired private ci.esatic.sigep.repository.RapportPdfRepository rapportPdfRepository;

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

    // ─── Suppression d'un enseignant ──────────────────────────────────────────
    // Incident de production : la suppression etait tentee a l'aveugle et la contrainte de cle
    // etrangere (rapports_pdf) remontait en erreur 500, sans rien expliquer a l'utilisateur.

    @Test
    @WithMockUser(username = "admin@esatic.ci", roles = "ADMIN")
    void suppression_estRefusee_quandDesRapportsExistent_etNeProduitPasUne500() throws Exception {
        var prof = enseignantRepository.findByMatricule("ENS-WEB-1").orElseThrow();
        rapportPdfRepository.save(ci.esatic.sigep.entity.RapportPdf.builder()
                .enseignant(prof)
                .periodeDebut(java.time.LocalDate.now().minusDays(7))
                .periodeFin(java.time.LocalDate.now())
                .nomFichier("rapport-test.pdf")
                .cheminFichier("target/test-rapports/rapport-test.pdf")
                .dateGeneration(java.time.LocalDateTime.now())
                .build());

        mockMvc.perform(post("/admin/enseignants/" + prof.getId() + "/supprimer").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error",
                        org.hamcrest.Matchers.containsString("Suppression impossible")))
                // Le message doit NOMMER ce qui bloque, sinon l'utilisateur reste sans recours.
                .andExpect(flash().attribute("error",
                        org.hamcrest.Matchers.containsString("rapport")));

        // L'enseignant et son rapport sont toujours la : rien n'a ete detruit au passage.
        assertThat(enseignantRepository.findById(prof.getId())).isPresent();
        assertThat(rapportPdfRepository.countByEnseignantId(prof.getId())).isEqualTo(1);
    }

    @Test
    @WithMockUser(username = "admin@esatic.ci", roles = "ADMIN")
    void suppression_reussit_quandRienNeDependDeLEnseignant() throws Exception {
        var prof = enseignantRepository.findByMatricule("ENS-WEB-1").orElseThrow();
        Long idCompte = prof.getUser() != null ? prof.getUser().getId() : null;

        mockMvc.perform(post("/admin/enseignants/" + prof.getId() + "/supprimer").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success",
                        org.hamcrest.Matchers.containsString("supprimé")));

        assertThat(enseignantRepository.findById(prof.getId())).isEmpty();
        // Le compte de connexion doit partir avec lui : un compte orphelin resterait capable
        // de se connecter a l'application mobile.
        if (idCompte != null) {
            assertThat(userRepository.findById(idCompte)).isEmpty();
        }
    }

    @Test
    @WithMockUser(username = "admin@esatic.ci", roles = "ADMIN")
    void suppression_dUnIdentifiantInconnu_neCassePas() throws Exception {
        mockMvc.perform(post("/admin/enseignants/999999/supprimer").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error",
                        org.hamcrest.Matchers.containsString("introuvable")));
    }

    // ─── Routage / sécurité ───────────────────────────────────────────────────

    @Test
    void racine_afficheLaPagePubliqueSaaS() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Inscrire mon établissement")));
    }

    @Test
    void inscription_afficheLeFormulairePublic() throws Exception {
        mockMvc.perform(get("/inscription"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Créer mon espace")));
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
    void abonnement_devraitSeRendreCorrectement() throws Exception {
        mockMvc.perform(get("/admin/abonnement"))
                .andExpect(status().isOk())
                // Titre de page stable (les libellés des sections de paiement évoluent).
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Abonnement")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Mobile Money")));
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
    void navigationInstantanee_estServieSurLesPagesAdmin() throws Exception {
        // Le script vit dans un fragment partage : une erreur d'inclusion le ferait disparaitre
        // silencieusement, et la navigation redeviendrait un rechargement complet sans que rien
        // ne signale la regression.
        mockMvc.perform(get("/admin/enseignants"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("navigationInstantanee")))
                // th:inline="none" protege le script : sans lui, une sequence de deux crochets
                // ouvrants en JavaScript est lue comme une expression Thymeleaf et casse le rendu
                // de TOUTES les pages d'administration. C'est deja arrive.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("th:inline"))));
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

    // ─── Filet de régression pour le découpage d'AdminWebController ─────────────
    // (référentiels / messages / statistiques / formulaire enseignant / POST / CSV)

    @Test
    @WithMockUser(username = "admin@esatic.ci", roles = "ADMIN")
    void referentiels_devraitSeRendreCorrectement() throws Exception {
        mockMvc.perform(get("/admin/referentiels")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin@esatic.ci", roles = "ADMIN")
    void messages_devraitSeRendreCorrectement() throws Exception {
        mockMvc.perform(get("/admin/messages")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin@esatic.ci", roles = "ADMIN")
    void statistiques_devraitSeRendreCorrectement() throws Exception {
        mockMvc.perform(get("/admin/statistiques")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin@esatic.ci", roles = "ADMIN")
    void nouvelEnseignantForm_devraitSeRendreCorrectement() throws Exception {
        mockMvc.perform(get("/admin/enseignants/nouveau")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin@esatic.ci", roles = "ADMIN")
    void creerMatiere_devraitRedirigerVersReferentiels() throws Exception {
        mockMvc.perform(post("/admin/matieres").param("libelle", "Algèbre linéaire").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/referentiels"));
    }

    @Test
    @WithMockUser(username = "admin@esatic.ci", roles = "ADMIN")
    void modeleEmploiDuTemps_devraitRenvoyerUnCsv() throws Exception {
        mockMvc.perform(get("/admin/planning/modele"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("modele_emploi_du_temps.csv")));
    }
}
