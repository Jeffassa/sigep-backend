package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Paiement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.PaiementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVP-2 : suivi des paiements. Valider un renouvellement enregistre un paiement + prolonge ;
 * l'historique est réservé au super admin ; la suppression RGPD efface aussi les paiements.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PaiementIntegrationTest {

    @MockitoBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PaiementRepository paiementRepository;
    @Autowired private ci.esatic.sigep.tenant.plan.PlanService planService;
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
                .nom("Plateforme").slug("pay-plt-" + n).build());
        cible = etablissementRepository.save(Etablissement.builder()
                .nom("Client Pay " + n).slug("pay-cli-" + n).plan(Plan.PRO).build());

        superAdmin = userRepository.save(User.builder()
                .email("pay-root-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleSuper))).etablissement(plateforme).build());
        adminClient = userRepository.save(User.builder()
                .email("pay-adm-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin))).etablissement(cible).build());
    }

    @Test
    void aucunAbonnementNeSeProlongeALaMain() throws Exception {
        // L'ancien formulaire « Valider » créditait un abonnement sans aucune transaction.
        int code = mockMvc.perform(post("/plateforme/abonnements/prolonger").with(user(superAdmin)).with(csrf())
                        .param("slug", cible.getSlug()).param("mois", "12").param("montant", "0"))
                .andReturn().getResponse().getStatus();
        assertThat(code).isGreaterThanOrEqualTo(400);

        em.flush(); em.clear();
        assertThat(paiementRepository.findByEtablissementIdOrderByDatePaiementDesc(cible.getId())).isEmpty();
        assertThat(etablissementRepository.findBySlug(cible.getSlug()).orElseThrow().getDateExpiration()).isNull();
    }

    @Test
    void laPageAbonnements_nOffreAucunFormulaireDeValidation() throws Exception {
        mockMvc.perform(get("/plateforme/abonnements").with(user(superAdmin)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(cible.getNom())))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("abonnements/prolonger"))));
    }

    @Test
    void historique_paiements_visible_par_le_super_admin() throws Exception {
        paiementRepository.save(Paiement.builder()
                .etablissementId(cible.getId()).montant(12345L).moisCredites(1)
                .reference("REF-VISIBLE").enregistrePar(superAdmin.getEmail()).build());
        em.flush();

        mockMvc.perform(get("/plateforme/paiements").with(user(superAdmin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("REF-VISIBLE")));
    }

    @Test
    void adminEtablissement_ne_voit_pas_les_paiements() throws Exception {
        mockMvc.perform(get("/plateforme/paiements").with(user(adminClient)))
                .andExpect(status().isForbidden());
    }

    @Test
    void suppression_rgpd_efface_aussi_les_paiements() throws Exception {
        paiementRepository.save(Paiement.builder()
                .etablissementId(cible.getId()).montant(5000L).moisCredites(1).build());
        em.flush();

        mockMvc.perform(post("/plateforme/etablissements/supprimer").with(user(superAdmin)).with(csrf())
                        .param("slug", cible.getSlug()))
                .andExpect(status().is3xxRedirection());

        em.flush(); em.clear();
        assertThat(etablissementRepository.findBySlug(cible.getSlug())).isEmpty();
        assertThat(paiementRepository.findByEtablissementIdOrderByDatePaiementDesc(cible.getId())).isEmpty();
    }
}
