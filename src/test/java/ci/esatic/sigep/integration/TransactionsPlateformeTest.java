package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import ci.esatic.sigep.service.MailService;
import ci.esatic.sigep.service.PaiementIntentStore;
import ci.esatic.sigep.service.TransactionsPlateformeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Toutes les transactions sont listées avec leur statut ; le super administrateur tranche celles
 * que le fournisseur n'a pas tranchées, et la fiche d'un établissement retrace ses abonnements.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional   // rien ne reste dans la base partagée
class TransactionsPlateformeTest {

    @MockitoBean private DataInitializer dataInitializer;
    @MockitoBean private MailService mailService;

    @Autowired private MockMvc mockMvc;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private PaiementIntentRepository intentRepository;
    @Autowired private PaiementRepository paiementRepository;
    @Autowired private PaiementIntentStore store;
    @Autowired private TransactionsPlateformeService service;

    private User superAdmin;
    private Etablissement ecole;
    private long n;

    @BeforeEach
    void setUp() {
        n = System.nanoTime();
        Role roleSuper = roleRepository.findByName(ERole.ROLE_SUPER_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_SUPER_ADMIN)));
        Etablissement plateforme = etablissementRepository.save(Etablissement.builder()
                .nom("SIGEP Plateforme").slug("plateforme-tx-" + n).build());
        ecole = etablissementRepository.save(Etablissement.builder().nom("École Transactions " + n)
                .slug("tx-" + n).plan(Plan.FREE).statut(StatutEtablissement.VALIDE).actif(true).build());
        superAdmin = userRepository.save(User.builder().email("root-tx-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleSuper))).etablissement(plateforme).build());
    }

    private PaiementIntent intention(String prefixe, StatutIntent statut, long montant) {
        return intentRepository.save(PaiementIntent.builder()
                .reference(prefixe + n + "-" + System.nanoTime()).etablissementId(ecole.getId())
                .plan(Plan.PRO).mois(1).montantAttendu(montant).devise("XOF").provider("WAVE")
                .msisdn("+2250700000000").statut(statut).build());
    }

    @Test
    void laListe_montreChaqueTransactionAvecSonStatut_etFiltre() throws Exception {
        PaiementIntent payee = intention("JK-", StatutIntent.REUSSI, 13_000);
        PaiementIntent aVerifier = intention("nova-", StatutIntent.A_VERIFIER, 39_000);

        mockMvc.perform(get("/plateforme/transactions").with(user(superAdmin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(payee.getReference())))
                .andExpect(content().string(containsString(aVerifier.getReference())))
                .andExpect(content().string(containsString("etat-payee")))
                .andExpect(content().string(containsString("etat-verifier")));

        mockMvc.perform(get("/plateforme/transactions").param("statut", "A_VERIFIER").with(user(superAdmin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(aVerifier.getReference())))
                .andExpect(content().string(not(containsString(payee.getReference()))));

        mockMvc.perform(get("/plateforme/transactions").param("q", "ecole transactions " + n).with(user(superAdmin)))
                .andExpect(content().string(containsString(payee.getReference())));
    }

    @Test
    void confirmerPayee_crediteLAbonnement_etGardeLaDecision() throws Exception {
        PaiementIntent t = intention("JK-", StatutIntent.A_VERIFIER, 13_000);

        mockMvc.perform(get("/plateforme/transactions/" + t.getReference()).with(user(superAdmin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Confirmer payée")))
                .andExpect(content().string(containsString("Classer non payée")));

        mockMvc.perform(post("/plateforme/transactions/" + t.getReference() + "/payee").with(user(superAdmin)).with(csrf())
                        .param("montant", "13000").param("note", "Encaissement visible chez Jèko"))
                .andExpect(flash().attributeExists("ok"));

        PaiementIntent apres = intentRepository.findByReference(t.getReference()).orElseThrow();
        assertThat(apres.getStatut()).isEqualTo(StatutIntent.REUSSI);
        assertThat(apres.getTraitePar()).isEqualTo(superAdmin.getEmail());
        assertThat(apres.getNoteTraitement()).isEqualTo("Encaissement visible chez Jèko");
        assertThat(apres.getDateTraitement()).isNotNull();

        Paiement p = paiementRepository.findFirstByReference("Jèko " + t.getReference()).orElseThrow();
        assertThat(p.getMontant()).isEqualTo(13_000);
        assertThat(p.getPeriodeDebut()).isEqualTo(LocalDate.now());
        assertThat(p.getPeriodeFin()).isEqualTo(LocalDate.now().plusMonths(1));
        Etablissement e = etablissementRepository.findById(ecole.getId()).orElseThrow();
        assertThat(e.getPlan()).isEqualTo(Plan.PRO);
        assertThat(e.getDateExpiration()).isEqualTo(LocalDate.now().plusMonths(1));

        // Une transaction payée ne se tranche plus : ni une seconde fois, ni en sens inverse.
        mockMvc.perform(post("/plateforme/transactions/" + t.getReference() + "/payee").with(user(superAdmin)).with(csrf())
                        .param("note", "doublon"))
                .andExpect(flash().attribute("erreur", "Cette transaction est déjà payée et créditée."));
        mockMvc.perform(post("/plateforme/transactions/" + t.getReference() + "/non-payee").with(user(superAdmin)).with(csrf())
                        .param("note", "erreur"))
                .andExpect(flash().attributeExists("erreur"));
        assertThat(paiementRepository.findByEtablissementIdOrderByDatePaiementDesc(ecole.getId())).hasSize(1);
    }

    @Test
    void classerNonPayee_neCrediteRien_etExigeUnConstat() throws Exception {
        PaiementIntent t = intention("nova-", StatutIntent.A_VERIFIER, 13_000);

        mockMvc.perform(post("/plateforme/transactions/" + t.getReference() + "/non-payee").with(user(superAdmin)).with(csrf())
                        .param("note", "  "))
                .andExpect(flash().attribute("erreur", "Indiquez ce que vous avez constaté chez le fournisseur."));
        assertThat(intentRepository.findByReference(t.getReference()).orElseThrow().getStatut())
                .isEqualTo(StatutIntent.A_VERIFIER);

        mockMvc.perform(post("/plateforme/transactions/" + t.getReference() + "/non-payee").with(user(superAdmin)).with(csrf())
                        .param("note", "Inconnue chez NovaSend : essai"))
                .andExpect(flash().attributeExists("ok"));
        PaiementIntent apres = intentRepository.findByReference(t.getReference()).orElseThrow();
        assertThat(apres.getStatut()).isEqualTo(StatutIntent.ECHOUE);
        assertThat(apres.getFournisseur()).isEqualTo("NovaSend");
        assertThat(paiementRepository.findByEtablissementIdOrderByDatePaiementDesc(ecole.getId())).isEmpty();
    }

    @Test
    void uneTransactionEncoreSuivie_neSeTranchePasALaMain() {
        PaiementIntent t = intention("JK-", StatutIntent.EN_COURS, 13_000);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.confirmerPayee(t.getReference(), null, "vu", "root"))
                .hasMessageContaining("suivie automatiquement");
    }

    @Test
    void laFiche_retraceLesAbonnements_memeAnciens() throws Exception {
        // Deux paiements antérieurs au suivi des périodes : la frise les recalcule, bout à bout.
        LocalDateTime il_y_a_2_mois = LocalDateTime.now().minusMonths(2);
        paiementRepository.save(Paiement.builder().etablissementId(ecole.getId()).montant(13_000).moisCredites(1)
                .reference("ancien-1-" + n).datePaiement(il_y_a_2_mois).plan(Plan.PRO).devise("XOF").build());
        paiementRepository.save(Paiement.builder().etablissementId(ecole.getId()).montant(39_000).moisCredites(3)
                .reference("ancien-2-" + n).datePaiement(il_y_a_2_mois.plusDays(1)).plan(Plan.PRO).devise("XOF").build());

        List<TransactionsPlateformeService.Periode> periodes = service.historique(ecole.getId());
        assertThat(periodes).hasSize(2);
        TransactionsPlateformeService.Periode recente = periodes.get(0);
        TransactionsPlateformeService.Periode ancienne = periodes.get(1);
        assertThat(ancienne.debut()).isEqualTo(il_y_a_2_mois.toLocalDate());
        assertThat(recente.debut()).isEqualTo(ancienne.fin());
        assertThat(recente.fin()).isEqualTo(ancienne.fin().plusMonths(3));
        assertThat(recente.enCours()).isTrue();
        assertThat(recente.reconstituee()).isTrue();
        assertThat(recente.getSource()).isEqualTo("Enregistré à la main");

        mockMvc.perform(get("/plateforme/etablissements/" + ecole.getSlug()).with(user(superAdmin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Historique des abonnements")))
                .andExpect(content().string(containsString("periode-marque\">En cours")))
                .andExpect(content().string(containsString("39 000 XOF")));
    }

    @Test
    void laPurge_effaceLeNumero_maisGardeLaTransaction() {
        PaiementIntent ancienne = intention("JK-", StatutIntent.ECHOUE, 13_000);
        PaiementIntent aVerifier = intention("JK-", StatutIntent.A_VERIFIER, 13_000);

        store.purger(LocalDateTime.now().plusMinutes(1));

        PaiementIntent apres = intentRepository.findByReference(ancienne.getReference()).orElseThrow();
        assertThat(apres.getMsisdn()).isNull();
        assertThat(apres.getStatut()).isEqualTo(StatutIntent.ECHOUE);
        // Tant qu'elle n'est pas tranchée, le numéro aide à la retrouver chez le fournisseur.
        assertThat(intentRepository.findByReference(aVerifier.getReference()).orElseThrow().getMsisdn()).isNotNull();
    }
}
