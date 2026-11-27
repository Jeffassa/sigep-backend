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
import ci.esatic.sigep.repository.AssistantConsoRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.JournalAgentRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.service.MailService;
import ci.esatic.sigep.service.assistant.AssistantConsoService;
import ci.esatic.sigep.service.assistant.ClientClaude;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Assistant de la console : activé par l'établissement, données pseudonymisées, écritures
 * confirmées par l'administrateur, journal. Le modèle est simulé : aucun appel réel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.ai.assistant.enabled=true", "app.ai.assistant.repli=false"})
class AssistantTest {

    @MockitoBean private DataInitializer dataInitializer;
    @MockitoBean private MailService mailService;
    @MockitoBean private ClientClaude claude;

    @Autowired private MockMvc mockMvc;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private JournalAgentRepository journalAgentRepository;
    @Autowired private AssistantConsoRepository consoRepository;
    @Autowired private AssistantConsoService consoService;

    private Etablissement etab;
    private User admin;
    private Enseignant prof;
    private final MockHttpSession session = new MockHttpSession();

    @BeforeEach
    void preparer() {
        long n = System.nanoTime();
        Role roleAdmin = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        etab = etablissementRepository.save(Etablissement.builder().nom("Etab Assistant").slug("assist-" + n)
                .plan(Plan.ENTERPRISE).build());
        admin = userRepository.save(User.builder().email("admin-assist-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin))).etablissement(etab).build());
        Enseignant e = Enseignant.builder().matricule("PX" + (n % 100000)).nom("Bamba").prenom("Awa")
                .statut(StatutEnseignant.VALIDATED).build();
        e.setEtablissementId(etab.getId());
        prof = enseignantRepository.save(e);
        when(claude.configure()).thenReturn(true);
        ReflectionTestUtils.setField(consoService, "maxRequetesJour", 100);
    }

    @AfterEach
    void nettoyer() {
        Long id = etab.getId();
        journalAgentRepository.deleteAll(journalAgentRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        consoRepository.deleteAll(consoRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        enseignantRepository.deleteAll(enseignantRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        userRepository.deleteAll(userRepository.findByEtablissementIdOrderByIdAsc(id));
        etablissementRepository.deleteById(id);
    }

    @Test
    void ilFautLActiverEnConnaissanceDeCause() throws Exception {
        mockMvc.perform(get("/admin/assistant").with(user(admin)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Activer l'assistant")))
                .andExpect(content().string(containsString("Anthropic")));
        mockMvc.perform(message("Bonjour")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSISTANT_INACTIF"));

        mockMvc.perform(post("/admin/assistant/activer").with(user(admin)).with(csrf()));
        assertThat(recharger().isAssistantActive()).isFalse(); // case non cochée

        mockMvc.perform(post("/admin/assistant/activer").with(user(admin)).with(csrf()).param("accord", "true"));
        Etablissement apres = recharger();
        assertThat(apres.isAssistantActive()).isTrue();
        assertThat(apres.getAssistantActivePar()).isEqualTo(admin.getEmail());
        assertThat(apres.getAssistantActiveLe()).isNotNull();
    }

    @Test
    void lesNomsPartentMasques_etReviennentEnClair() throws Exception {
        activer();
        when(claude.envoyer(any())).thenReturn(
                outil("chercher_enseignants", Map.of("texte", "")),
                texte("J'ai trouvé Prenom_1 Nom_2."));

        mockMvc.perform(message("Retrouve Awa Bamba"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.texte").value("J'ai trouvé Awa Bamba."));

        ArgumentCaptor<MessageCreateParams> envoye = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(claude, atLeastOnce()).envoyer(envoye.capture());
        String toutCeQuiEstParti = envoye.getAllValues().toString();
        assertThat(toutCeQuiEstParti).doesNotContain("Bamba", "Awa", prof.getMatricule())
                .contains("Prenom_", "Nom_", "chercher_enseignants");
        assertThat(journal()).anyMatch(j -> "ASSISTANT".equals(j.getSource())
                && j.getChemin().equals("assistant/chercher_enseignants") && j.getCleId() == null);
    }

    @Test
    void uneEcritureAttendLeClicDeLAdministrateur() throws Exception {
        activer();
        when(claude.envoyer(any())).thenReturn(
                outil("regler_tolerance", Map.of("avant", 10, "apres", 20)),
                texte("Si vous confirmez, la tolérance passera à 10 et 20 minutes."));

        String corps = mockMvc.perform(message("Mets la tolérance à 10 minutes avant et 20 après"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].libelle").value("Régler la tolérance"))
                .andReturn().getResponse().getContentAsString();
        assertThat(recharger().getToleranceAvantMinutes()).isEqualTo(15); // rien n'a changé
        String id = corps.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/admin/assistant/actions/" + id + "/confirmer").with(user(admin)).with(csrf()).session(session))
                .andExpect(status().isOk());
        assertThat(recharger().getToleranceAvantMinutes()).isEqualTo(10);
        assertThat(recharger().getToleranceApresMinutes()).isEqualTo(20);
        assertThat(journal()).anyMatch(j -> j.getChemin().equals("assistant/regler_tolerance") && j.isSimulation())
                .anyMatch(j -> j.getChemin().equals("assistant/regler_tolerance") && !j.isSimulation());

        // Une action confirmée ne se rejoue pas.
        mockMvc.perform(post("/admin/assistant/actions/" + id + "/confirmer").with(user(admin)).with(csrf()).session(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unRefusDeSigepRevientAuModele_sansProposerDAction() throws Exception {
        activer();
        when(claude.envoyer(any())).thenReturn(
                outil("regler_tolerance", Map.of("avant", 10, "apres", 500)),
                texte("SIGEP refuse cette tolérance."));
        mockMvc.perform(message("Tolérance de 500 minutes"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actions").isEmpty());
    }

    @Test
    void horsDuPlanEntreprise_ouAuDelaDuQuota_cEstNon() throws Exception {
        activer();
        ReflectionTestUtils.setField(consoService, "maxRequetesJour", 1);
        when(claude.envoyer(any())).thenReturn(texte("Bonjour."));
        mockMvc.perform(message("Bonjour")).andExpect(status().isOk()).andExpect(jsonPath("$.restantes").value(0));
        mockMvc.perform(message("Encore")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUOTA_ASSISTANT"));

        Etablissement e = recharger();
        e.setPlan(Plan.PRO);
        etablissementRepository.save(e);
        mockMvc.perform(message("Bonjour")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PLAN"));
    }

    @Test
    void unePanneDuFournisseur_nEntamePasLeQuota_etLaConversationResteValide() throws Exception {
        activer();
        ReflectionTestUtils.setField(consoService, "maxRequetesJour", 1);
        when(claude.envoyer(any())).thenThrow(new IllegalStateException("crédit épuisé")).thenReturn(texte("Bonjour."));
        mockMvc.perform(message("Bonjour")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSISTANT_INDISPONIBLE"));
        mockMvc.perform(message("Bonjour")).andExpect(status().isOk()).andExpect(jsonPath("$.texte").value("Bonjour."));
    }

    @Test
    void unMembreDeLEquipe_nOuvrePasLAssistant() throws Exception {
        Role compta = roleRepository.findByName(ERole.ROLE_COMPTABILITE)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_COMPTABILITE)));
        User membre = userRepository.save(User.builder().email("compta-" + System.nanoTime() + "@test.local")
                .password("{noop}x").roles(new HashSet<>(Set.of(compta))).etablissement(etab).build());
        mockMvc.perform(get("/admin/assistant").with(user(membre))).andExpect(status().isForbidden());
    }

    // ── Aides ──

    private MockHttpServletRequestBuilder message(String texte) {
        return post("/admin/assistant/message").with(user(admin)).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"texte\":\"" + texte + "\"}");
    }

    private void activer() {
        Etablissement e = recharger();
        e.setAssistantActive(true);
        etablissementRepository.save(e);
    }

    private Etablissement recharger() {
        return etablissementRepository.findById(etab.getId()).orElseThrow();
    }

    private List<JournalAgent> journal() {
        return journalAgentRepository.findAll().stream().filter(j -> etab.getId().equals(j.getEtablissementId())).toList();
    }

    private static Message outil(String nom, Map<String, Object> arguments) {
        return reponse(StopReason.TOOL_USE, ContentBlock.ofToolUse(ToolUseBlock.builder()
                .id("toolu_" + nom).name(nom).input(JsonValue.from(arguments))
                .caller(DirectCaller.builder().build()).build()));
    }

    private static Message texte(String texte) {
        return reponse(StopReason.END_TURN, ContentBlock.ofText(TextBlock.builder().text(texte).citations(Optional.empty()).build()));
    }

    private static Message reponse(StopReason fin, ContentBlock bloc) {
        return Message.builder()
                .id("msg_test").model("claude-opus-5-5").container(Optional.empty()).stopDetails(Optional.empty())
                .stopReason(fin).stopSequence(Optional.empty())
                .content(List.of(bloc))
                .usage(Usage.builder().inputTokens(100).outputTokens(20).cacheCreation(Optional.empty())
                        .cacheCreationInputTokens(Optional.empty()).cacheReadInputTokens(Optional.empty())
                        .inferenceGeo(Optional.empty()).serverToolUse(Optional.empty()).serviceTier(Optional.empty()).build())
                .build();
    }
}
