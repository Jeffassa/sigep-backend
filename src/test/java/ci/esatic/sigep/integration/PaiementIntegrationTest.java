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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PaiementRepository paiementRepository;
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
    void validerUnRenouvellement_enregistreUnPaiement_etProlonge() throws Exception {
        mockMvc.perform(post("/plateforme/abonnements/prolonger").with(user(superAdmin)).with(csrf())
                        .param("slug", cible.getSlug()).param("mois", "3")
                        .param("montant", "30000").param("reference", "TX-001"))
                .andExpect(status().is3xxRedirection());

        em.flush(); em.clear();
        List<Paiement> paiements = paiementRepository.findByEtablissementIdOrderByDatePaiementDesc(cible.getId());
        assertThat(paiements).hasSize(1);
        assertThat(paiements.get(0).getMontant()).isEqualTo(30000L);
        assertThat(paiements.get(0).getMoisCredites()).isEqualTo(3);
        assertThat(paiements.get(0).getReference()).isEqualTo("TX-001");
        assertThat(paiements.get(0).getEnregistrePar()).isEqualTo(superAdmin.getEmail());
        assertThat(paiementRepository.totalEncaisse()).isEqualTo(30000L);

        // L'abonnement a bien été prolongé (date d'expiration désormais renseignée et future).
        assertThat(etablissementRepository.findBySlug(cible.getSlug()).orElseThrow().getDateExpiration())
                .isNotNull();
    }

    @Test
    void montant_absent_utilise_le_tarif_du_plan() throws Exception {
        // Pas de montant fourni → mois × prix du plan Pro (10 000 FCFA par défaut).
        mockMvc.perform(post("/plateforme/abonnements/prolonger").with(user(superAdmin)).with(csrf())
                        .param("slug", cible.getSlug()).param("mois", "2"))
                .andExpect(status().is3xxRedirection());
        em.flush(); em.clear();
        assertThat(paiementRepository.totalEncaisse()).isEqualTo(20000L);
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
