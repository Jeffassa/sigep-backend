package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.JournalAgent;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.CleAgentRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.JournalAgentRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.security.AgentAuthFilter;
import ci.esatic.sigep.service.CleAgentService;
import ci.esatic.sigep.service.MailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API de l'agent : un assistant agit sur le tableau de bord d'un établissement.
 *
 * <p>Ce qui est fixé ici : l'agent n'a jamais plus de droits que l'administrateur qui l'a créé
 * (même établissement, mêmes règles que le formulaire, droits relus à chaque appel), la
 * simulation n'écrit rien, et tout ce qu'il fait laisse une trace.
 *
 * <p>Pile MVC réelle, sans {@code @Transactional} (comme les autres tests d'isolation) :
 * nettoyage manuel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentApiTest {

    @MockitoBean private DataInitializer dataInitializer;
    @MockitoBean private MailService mailService;

    @Autowired private MockMvc mockMvc;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CleAgentRepository cleAgentRepository;
    @Autowired private JournalAgentRepository journalAgentRepository;
    @Autowired private CleAgentService cleAgentService;

    private Etablissement etabA;
    private Etablissement etabB;
    private User adminDeA;
    private Enseignant enseignantDeB;
    private String cle;
    private long n;

    @BeforeEach
    void preparer() {
        Role roleAdmin = role(ERole.ROLE_ADMIN);
        role(ERole.ROLE_ENSEIGNANT);
        n = System.nanoTime();

        etabA = etablissementRepository.save(Etablissement.builder()
                .nom("Etab Agent A").slug("agent-a-" + n).plan(Plan.ENTERPRISE).maxEnseignants(0).build());
        etabB = etablissementRepository.save(Etablissement.builder()
                .nom("Etab Agent B").slug("agent-b-" + n).plan(Plan.ENTERPRISE).build());

        Enseignant e = Enseignant.builder().matricule("B-" + n).nom("Etranger").prenom("Invisible")
                .statut(StatutEnseignant.VALIDATED).build();
        e.setEtablissementId(etabB.getId());
        enseignantDeB = enseignantRepository.save(e);

        adminDeA = userRepository.save(User.builder()
                .email("admin-agent-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin))).etablissement(etabA).build());

        cle = cleAgentService.emettre(adminDeA, "JEFF");
    }

    @AfterEach
    void nettoyer() {
        journalAgentRepository.deleteAll(journalAgentRepository.findAll().stream()
                .filter(j -> j.getEtablissementId().equals(etabA.getId())).toList());
        cleAgentRepository.deleteAll(cleAgentRepository.findAll().stream()
                .filter(c -> c.getEtablissementId().equals(etabA.getId())).toList());
        List<Enseignant> fiches = enseignantRepository.findAll().stream()
                .filter(f -> f.getEtablissementId().equals(etabA.getId())
                        || f.getEtablissementId().equals(etabB.getId())).toList();
        List<User> comptes = fiches.stream().map(Enseignant::getUser).filter(u -> u != null).toList();
        enseignantRepository.deleteAll(fiches);
        userRepository.deleteAll(comptes);
        userRepository.deleteById(adminDeA.getId());
        etablissementRepository.deleteById(etabA.getId());
        etablissementRepository.deleteById(etabB.getId());
    }

    private Role role(ERole nom) {
        return roleRepository.findByName(nom).orElseGet(() -> roleRepository.save(new Role(null, nom)));
    }

    private String corpsCreation(String matricule) {
        return """
                {"matricule":"%s","nom":"Kouassi","prenom":"Awa","departement":"Maths","email":"awa-%d@test.local"}
                """.formatted(matricule, n);
    }

    private List<JournalAgent> journal() {
        return journalAgentRepository.findAll().stream()
                .filter(j -> j.getEtablissementId().equals(etabA.getId())).toList();
    }

    @Test
    void sansCle_ouAvecUneCleInventee_lAgentEstRefuse() throws Exception {
        mockMvc.perform(get("/api/agent/moi")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/agent/moi").header(AgentAuthFilter.ENTETE, "sigep_agent_invente"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lAgent_agitAuNomDeSonAdministrateur() throws Exception {
        mockMvc.perform(get("/api/agent/moi").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.administrateur").value(adminDeA.getEmail()))
                .andExpect(jsonPath("$.data.etablissement").value("Etab Agent A"));
    }

    @Test
    void creerUnEnseignant_suitLesReglesDuFormulaire_etLaisseUneTrace() throws Exception {
        mockMvc.perform(post("/api/agent/enseignants").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content(corpsCreation("A-" + n)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.issue").value("CREE"))
                .andExpect(jsonPath("$.data.statut").value("VALIDATED"));

        Enseignant cree = enseignantRepository.findAll().stream()
                .filter(f -> ("A-" + n).equals(f.getMatricule())).findFirst().orElseThrow();
        // Rattaché à l'établissement de l'administrateur, compte compris.
        assertThat(cree.getEtablissementId()).isEqualTo(etabA.getId());
        assertThat(cree.getUser().getEtablissement().getId()).isEqualTo(etabA.getId());
        assertThat(cree.getUser().isMustChangePassword()).isTrue();
        verify(mailService).notifierIdentifiantsProvisoires(any(), eq("awa-" + n + "@test.local"), eq("Awa"), anyString());

        assertThat(journal()).anySatisfy(j -> {
            assertThat(j.getResume()).contains("Awa Kouassi").contains("est créé");
            assertThat(j.isSimulation()).isFalse();
            assertThat(j.getStatutHttp()).isEqualTo(201);
            assertThat(j.getUserId()).isEqualTo(adminDeA.getId());
        });

        // Le même matricule une seconde fois : refusé, comme à l'écran.
        mockMvc.perform(post("/api/agent/enseignants").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content(corpsCreation("A-" + n)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("MATRICULE_PRIS"));
    }

    @Test
    void laSimulation_nEcritRien_etNEnvoieRien() throws Exception {
        mockMvc.perform(post("/api/agent/enseignants").param("simulation", "true")
                        .header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content(corpsCreation("SIM-" + n)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.simulation").value(true))
                .andExpect(jsonPath("$.message").value(containsString("serait créé")));

        assertThat(enseignantRepository.findAll()).noneMatch(f -> ("SIM-" + n).equals(f.getMatricule()));
        verify(mailService, never()).notifierIdentifiantsProvisoires(any(), any(), any(), any());
        assertThat(journal()).anySatisfy(j -> assertThat(j.isSimulation()).isTrue());
    }

    @Test
    void leQuotaDuPlan_bloqueAussiLAgent() throws Exception {
        etabA.setMaxEnseignants(1);
        etablissementRepository.save(etabA);
        Enseignant deja = Enseignant.builder().matricule("Q-" + n).nom("Deja").prenom("La").build();
        deja.setEtablissementId(etabA.getId());
        enseignantRepository.save(deja);

        mockMvc.perform(post("/api/agent/enseignants").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content(corpsCreation("A2-" + n)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("QUOTA_ATTEINT"));
    }

    @Test
    void lAgent_neVoitPasLesEnseignantsDUnAutreEtablissement() throws Exception {
        mockMvc.perform(get("/api/agent/enseignants").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Invisible"))));
        mockMvc.perform(get("/api/agent/enseignants/" + enseignantDeB.getId()).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/agent/enseignants/" + enseignantDeB.getId() + "/statut")
                        .header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"statut\":\"ARCHIVE\"}"))
                .andExpect(status().isNotFound());
        assertThat(enseignantRepository.findById(enseignantDeB.getId()).orElseThrow().getStatut())
                .isEqualTo(StatutEnseignant.VALIDATED);
    }

    @Test
    void unAdministrateurQuiPerdSesDroits_emporteSonAgent() throws Exception {
        adminDeA.setRoles(new HashSet<>(Set.of(role(ERole.ROLE_DIRECTION))));
        userRepository.save(adminDeA);

        mockMvc.perform(get("/api/agent/moi").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isForbidden());
        assertThat(journal()).anySatisfy(j -> assertThat(j.getStatutHttp()).isEqualTo(403));
    }

    @Test
    void sansLePlanEntreprise_lAgentEstRefuse() throws Exception {
        etabA.setPlan(Plan.PRO);
        etablissementRepository.save(etabA);

        mockMvc.perform(get("/api/agent/moi").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("plan Entreprise")));
    }

    @Test
    void uneCleRevoquee_neSertPlus() throws Exception {
        Long id = cleAgentRepository.findAll().stream()
                .filter(c -> c.getEtablissementId().equals(etabA.getId())).findFirst().orElseThrow().getId();
        cleAgentService.revoquer(id);

        mockMvc.perform(get("/api/agent/moi").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void laConsole_montreLesClesEtCeQueLAgentAFait() throws Exception {
        mockMvc.perform(post("/api/agent/enseignants").param("simulation", "true")
                        .header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content(corpsCreation("VU-" + n)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/agent").with(user(adminDeA)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("JEFF")))
                .andExpect(content().string(containsString("serait créé")))
                .andExpect(content().string(not(containsString(cle))));

        mockMvc.perform(post("/admin/agent/cles").with(user(adminDeA)).with(csrf()).param("libelle", "Second"))
                .andExpect(status().is3xxRedirection());
        assertThat(cleAgentRepository.findAll()).anyMatch(c -> "Second".equals(c.getLibelle())
                && c.getUserId().equals(adminDeA.getId()) && c.getEtablissementId().equals(etabA.getId()));
    }

    @Test
    void laCle_nEstConserveeQueSousFormeDEmpreinte() {
        assertThat(cle).startsWith("sigep_agent_");
        assertThat(cleAgentRepository.findAll()).noneMatch(c -> c.getEmpreinte().contains(cle)
                || cle.equals(c.getPrefixe()));
    }
}
