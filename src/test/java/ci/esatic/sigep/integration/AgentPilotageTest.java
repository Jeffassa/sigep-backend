package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.Classe;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Matiere;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.Salle;
import ci.esatic.sigep.entity.Seance;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.StatutSeance;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.ClasseRepository;
import ci.esatic.sigep.repository.CleAgentRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.JournalAgentRepository;
import ci.esatic.sigep.repository.MatiereRepository;
import ci.esatic.sigep.repository.RapportPdfRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.SalleRepository;
import ci.esatic.sigep.repository.SeanceRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API de l'agent : paie, rapports, statistiques, écran d'émargement, équipe, logo, abonnement.
 * Une simulation ne laisse aucune trace et n'envoie rien.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentPilotageTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    @MockitoBean private DataInitializer dataInitializer;
    @MockitoBean private MailService mailService;

    @Autowired private MockMvc mockMvc;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private MatiereRepository matiereRepository;
    @Autowired private ClasseRepository classeRepository;
    @Autowired private SalleRepository salleRepository;
    @Autowired private SeanceRepository seanceRepository;
    @Autowired private RapportPdfRepository rapportPdfRepository;
    @Autowired private CleAgentRepository cleAgentRepository;
    @Autowired private JournalAgentRepository journalAgentRepository;
    @Autowired private CleAgentService cleAgentService;

    private Etablissement etab;
    private User admin;
    private String cle;

    @Test
    void laPaieSeLitEtSExporte() throws Exception {
        String mois = LocalDate.now().minusDays(2).toString().substring(0, 7);
        mockMvc.perform(get("/api/agent/paie").param("mois", mois).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mois").value(mois))
                .andExpect(jsonPath("$.data.lignes.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.totaux.enseignantsActifs").exists());

        byte[] xlsx = mockMvc.perform(get("/api/agent/paie/export").param("mois", mois).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(xlsx, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        mockMvc.perform(get("/api/agent/paie/export").param("format", "csv").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", containsString(".csv")));

        mockMvc.perform(get("/api/agent/paie/export").param("format", "pdf").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("FORMAT_INVALIDE"));
        mockMvc.perform(get("/api/agent/paie").param("mois", "novembre").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("MOIS_INVALIDE"));
    }

    @Test
    void lesRapports_seGenerentSeulementPourDeVrai_puisSeTelechargent() throws Exception {
        String periode = "{\"debut\":\"" + LocalDate.now().minusDays(7) + "\",\"fin\":\"" + LocalDate.now() + "\"}";
        mockMvc.perform(post("/api/agent/rapports/generer").param("simulation", "true")
                        .contentType(MediaType.APPLICATION_JSON).content(periode).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enseignants").value(1));
        assertThat(rapports()).isEmpty();

        mockMvc.perform(post("/api/agent/rapports/generer")
                        .contentType(MediaType.APPLICATION_JSON).content(periode).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.generes").value(1));
        Long id = rapports().get(0);

        mockMvc.perform(get("/api/agent/rapports").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rapports[0].id").value(id));
        byte[] pdf = mockMvc.perform(get("/api/agent/rapports/" + id + "/fichier").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        mockMvc.perform(get("/api/agent/rapports/synthese").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", containsString("synthese_")));

        mockMvc.perform(post("/api/agent/rapports/generer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debut\":\"2026-12-10\",\"fin\":\"2026-12-01\"}").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("PERIODE_INVALIDE"));
    }

    @Test
    void lesStatistiques_sansLaCarteDeChaleur() throws Exception {
        mockMvc.perform(get("/api/agent/statistiques").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taux").exists())
                .andExpect(jsonPath("$.data.recommandations").exists())
                .andExpect(jsonPath("$.data.heatmap").doesNotExist());
    }

    @Test
    void laZoneEtLeReseauDeLEcran_simulesPuisReels() throws Exception {
        String zone = "{\"coordonnees\":\"5.3364, -4.0267\",\"rayon\":300}";
        mockMvc.perform(post("/api/agent/ecran/zone").param("simulation", "true")
                        .contentType(MediaType.APPLICATION_JSON).content(zone).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value(containsString("serait réglée")));
        assertThat(recharger().isZoneDefinie()).isFalse();

        mockMvc.perform(post("/api/agent/ecran/zone")
                        .contentType(MediaType.APPLICATION_JSON).content(zone).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk());
        assertThat(recharger().getZoneRayonMetres()).isEqualTo(300);

        mockMvc.perform(post("/api/agent/ecran/zone").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordonnees\":\"ici\"}").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("ZONE_INVALIDE"));
        mockMvc.perform(post("/api/agent/ecran/zone").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coordonnees\":\"5.3, -4.0\",\"rayon\":5}").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("ZONE_INVALIDE"));

        mockMvc.perform(post("/api/agent/ecran/reseau").param("simulation", "true")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reseaux\":\"41.202.0.0/16\"}")
                        .header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk());
        assertThat(recharger().getEcranReseaux()).isNull();
        mockMvc.perform(post("/api/agent/ecran/reseau")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reseaux\":\"41.202.0.0/16\"}")
                        .header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk());
        assertThat(recharger().getEcranReseaux()).isEqualTo("41.202.0.0/16");
        mockMvc.perform(post("/api/agent/ecran/reseau")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reseaux\":\"pas un réseau\"}")
                        .header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("RESEAU_INVALIDE"))
                .andExpect(jsonPath("$.message").value(containsString("Adresse illisible")));

        mockMvc.perform(post("/api/agent/ecran/zone/retirer").header(AgentAuthFilter.ENTETE, cle)).andExpect(status().isOk());
        assertThat(recharger().isZoneDefinie()).isFalse();
        mockMvc.perform(get("/api/agent/ecran").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reseaux").value("41.202.0.0/16"));
    }

    @Test
    void inviterUnMembre_laSimulationNeCreeNiNEnvoieRien() throws Exception {
        String email = "compta-" + System.nanoTime() + "@test.local";
        String corps = "{\"email\":\"" + email + "\",\"profil\":\"comptabilite\"}";
        mockMvc.perform(post("/api/agent/equipe").param("simulation", "true")
                        .contentType(MediaType.APPLICATION_JSON).content(corps).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value(containsString("serait créé")));
        assertThat(userRepository.existsByEmail(email)).isFalse();
        verify(mailService, never()).notifierCompteAdministration(any(), anyString(), any(), any(), any());

        mockMvc.perform(post("/api/agent/equipe")
                        .contentType(MediaType.APPLICATION_JSON).content(corps).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk());
        assertThat(userRepository.existsByEmail(email)).isTrue();
        verify(mailService).notifierCompteAdministration(any(), eq(email), any(), eq("Comptabilité"), any());

        mockMvc.perform(post("/api/agent/equipe")
                        .contentType(MediaType.APPLICATION_JSON).content(corps).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("EMAIL_PRIS"));
        // Un administrateur principal ne s'ajoute pas par l'agent.
        mockMvc.perform(post("/api/agent/equipe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@test.local\",\"profil\":\"admin\"}").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("PROFIL_INVALIDE"));
        mockMvc.perform(get("/api/agent/equipe").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.membres.length()").value(2));
    }

    @Test
    void leLogo_controleLImageAvantDeLaGarder() throws Exception {
        MockMultipartFile png = new MockMultipartFile("fichier", "logo.png", "image/png", PNG);
        mockMvc.perform(multipart("/api/agent/logo").file(png).param("simulation", "true").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk());
        assertThat(recharger().getLogoDonnees()).isNull();
        mockMvc.perform(multipart("/api/agent/logo").file(png).header(AgentAuthFilter.ENTETE, cle)).andExpect(status().isOk());
        assertThat(recharger().getLogoDonnees()).isEqualTo(PNG);

        MockMultipartFile faux = new MockMultipartFile("fichier", "logo.png", "image/png", "<html>".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/agent/logo").file(faux).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("IMAGE_INVALIDE"));
        MockMultipartFile svg = new MockMultipartFile("fichier", "logo.svg", "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/agent/logo").file(svg).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("FORMAT_NON_PRIS"));

        mockMvc.perform(post("/api/agent/logo/retirer").header(AgentAuthFilter.ENTETE, cle)).andExpect(status().isOk());
        assertThat(recharger().getLogoDonnees()).isNull();
    }

    @Test
    void lAbonnement_etLeJournalDesSimulations() throws Exception {
        mockMvc.perform(get("/api/agent/abonnement").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.plan").value("ENTERPRISE"))
                .andExpect(jsonPath("$.data.enseignants").value(1));
        mockMvc.perform(post("/api/agent/logo/retirer").param("simulation", "true").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk());
        assertThat(journalAgentRepository.findAll().stream().filter(j -> etab.getId().equals(j.getEtablissementId())))
                .anyMatch(j -> j.isSimulation() && j.getChemin().contains("/logo/retirer"));
    }

    @BeforeEach
    void preparer() {
        long n = System.nanoTime() % 1_000_000;
        Role roleAdmin = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        roleRepository.findByName(ERole.ROLE_COMPTABILITE)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_COMPTABILITE)));
        etab = etablissementRepository.save(Etablissement.builder()
                .nom("Etab Pilotage").slug("agent-pil-" + n + System.nanoTime()).plan(Plan.ENTERPRISE).build());
        admin = userRepository.save(User.builder().email("admin-pil-" + System.nanoTime() + "@test.local")
                .password("{noop}x").roles(new HashSet<>(Set.of(roleAdmin))).etablissement(etab).build());
        Enseignant prof = enseignantRepository.save(tenant(Enseignant.builder().matricule("PL" + n).nom("Bamba")
                .prenom("Awa").statut(StatutEnseignant.VALIDATED).build()));
        Matiere matiere = matiereRepository.save(tenant(Matiere.builder().libelle("Algebre" + n).build()));
        Classe classe = classeRepository.save(tenant(Classe.builder().libelle("L1-" + n).build()));
        Salle salle = salleRepository.save(tenant(Salle.builder().libelle("S" + n).build()));
        seanceRepository.save(tenant(Seance.builder().date(LocalDate.now().minusDays(2)).heureDebut(LocalTime.of(8, 0))
                .heureFin(LocalTime.of(10, 0)).matiere(matiere).classe(classe).salle(salle).enseignant(prof)
                .statut(StatutSeance.A_FAIRE).build()));
        cle = cleAgentService.emettre(admin, "JEFF");
    }

    @AfterEach
    void nettoyer() {
        Long id = etab.getId();
        journalAgentRepository.deleteAll(journalAgentRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        cleAgentRepository.deleteAll(cleAgentRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        rapportPdfRepository.deleteAll(rapportPdfRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        seanceRepository.deleteAll(seanceRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        enseignantRepository.deleteAll(enseignantRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        salleRepository.deleteAll(salleRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        classeRepository.deleteAll(classeRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        matiereRepository.deleteAll(matiereRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        userRepository.deleteAll(userRepository.findByEtablissementIdOrderByIdAsc(id));
        etablissementRepository.deleteById(id);
    }

    private List<Long> rapports() {
        return rapportPdfRepository.findAll().stream().filter(r -> etab.getId().equals(r.getEtablissementId()))
                .map(r -> r.getId()).toList();
    }

    private Etablissement recharger() {
        return etablissementRepository.findById(etab.getId()).orElseThrow();
    }

    private <T extends ci.esatic.sigep.tenant.TenantScoped> T tenant(T entite) {
        entite.setEtablissementId(etab.getId());
        return entite;
    }
}
