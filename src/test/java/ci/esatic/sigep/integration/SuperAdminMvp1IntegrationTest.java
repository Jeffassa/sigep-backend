package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Super admin MVP-1 : gestion des établissements (plan, quota, suspension, suppression RGPD)
 * réservée au rôle SUPER_ADMIN.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SuperAdminMvp1IntegrationTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntityManager em;

    private User superAdmin;
    private User adminClient;
    private Etablissement cible;

    @BeforeEach
    void setUp() {
        Role roleAdmin = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        Role roleSuper = roleRepository.findByName(ERole.ROLE_SUPER_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_SUPER_ADMIN)));

        long n = System.nanoTime();
        Etablissement plateforme = etablissementRepository.save(Etablissement.builder()
                .nom("Plateforme").slug("mvp1-plt-" + n).build());
        cible = etablissementRepository.save(Etablissement.builder()
                .nom("Cible " + n).slug("mvp1-cible-" + n).plan(Plan.FREE).maxEnseignants(10).build());

        superAdmin = userRepository.save(User.builder()
                .email("mvp1-root-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleSuper))).etablissement(plateforme).build());
        adminClient = userRepository.save(User.builder()
                .email("mvp1-adm-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin))).etablissement(cible).build());
    }

    @Test
    void superAdmin_changeLePlan_ajusteAussiLeQuota() throws Exception {
        mockMvc.perform(post("/plateforme/etablissements/plan").with(user(superAdmin)).with(csrf())
                        .param("slug", cible.getSlug()).param("plan", "PRO"))
                .andExpect(status().is3xxRedirection());

        em.flush(); em.clear();
        Etablissement maj = etablissementRepository.findBySlug(cible.getSlug()).orElseThrow();
        assertThat(maj.getPlan()).isEqualTo(Plan.PRO);
        assertThat(maj.getMaxEnseignants()).isZero(); // illimité
    }

    @Test
    void superAdmin_suspend_puis_reactive() throws Exception {
        mockMvc.perform(post("/plateforme/etablissements/actif").with(user(superAdmin)).with(csrf())
                        .param("slug", cible.getSlug()).param("actif", "false"))
                .andExpect(status().is3xxRedirection());
        em.flush(); em.clear();
        assertThat(etablissementRepository.findBySlug(cible.getSlug()).orElseThrow().isActif()).isFalse();

        mockMvc.perform(post("/plateforme/etablissements/actif").with(user(superAdmin)).with(csrf())
                        .param("slug", cible.getSlug()).param("actif", "true"))
                .andExpect(status().is3xxRedirection());
        em.flush(); em.clear();
        assertThat(etablissementRepository.findBySlug(cible.getSlug()).orElseThrow().isActif()).isTrue();
    }

    @Test
    void superAdmin_supprime_effaceEtablissementEtSesDonnees() throws Exception {
        // On dote la cible d'un utilisateur + un enseignant, puis on supprime tout.
        Long etabId = cible.getId();
        Enseignant ens = Enseignant.builder()
                .matricule("MVP1-ENS").nom("N").prenom("P").statut(StatutEnseignant.PENDING)
                .etablissementId(etabId).build();
        enseignantRepository.save(ens);
        em.flush();

        assertThat(userRepository.findByEtablissementIdOrderByIdAsc(etabId)).isNotEmpty();

        mockMvc.perform(post("/plateforme/etablissements/supprimer").with(user(superAdmin)).with(csrf())
                        .param("slug", cible.getSlug()))
                .andExpect(status().is3xxRedirection());

        em.flush(); em.clear();
        assertThat(etablissementRepository.findBySlug(cible.getSlug())).isEmpty();
        assertThat(userRepository.findByEtablissementIdOrderByIdAsc(etabId)).isEmpty();
        assertThat(enseignantRepository.countByEtablissementId(etabId)).isZero();
    }

    @Test
    void adminEtablissement_ne_peut_pas_gerer_un_etablissement() throws Exception {
        mockMvc.perform(post("/plateforme/etablissements/plan").with(user(adminClient)).with(csrf())
                        .param("slug", cible.getSlug()).param("plan", "PRO"))
                .andExpect(status().isForbidden());
    }

    @Test
    void action_sur_letablissement_plateforme_est_refusee() throws Exception {
        Etablissement plateforme = etablissementRepository.save(Etablissement.builder()
                .nom("Techn").slug(DataInitializer.SLUG_PLATEFORME).build());
        mockMvc.perform(post("/plateforme/etablissements/supprimer").with(user(superAdmin)).with(csrf())
                        .param("slug", DataInitializer.SLUG_PLATEFORME))
                .andExpect(status().is3xxRedirection());
        em.flush(); em.clear();
        assertThat(etablissementRepository.findBySlug(DataInitializer.SLUG_PLATEFORME)).isPresent();
    }
}
