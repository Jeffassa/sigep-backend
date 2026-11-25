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
import ci.esatic.sigep.repository.CampusRepository;
import ci.esatic.sigep.repository.ClasseRepository;
import ci.esatic.sigep.repository.CleAgentRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.JournalAgentRepository;
import ci.esatic.sigep.repository.MatiereRepository;
import ci.esatic.sigep.repository.PeriodeCalendrierRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API de l'agent : référentiels, calendrier, import d'emploi du temps. La simulation joue l'action
 * puis l'annule : elle dit vrai (mêmes règles, mêmes contraintes) et ne laisse aucune trace.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentReferentielTest {

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
    @Autowired private CampusRepository campusRepository;
    @Autowired private SeanceRepository seanceRepository;
    @Autowired private PeriodeCalendrierRepository periodeRepository;
    @Autowired private CleAgentRepository cleAgentRepository;
    @Autowired private JournalAgentRepository journalAgentRepository;
    @Autowired private CleAgentService cleAgentService;

    private Etablissement etab;
    private User admin;
    private Enseignant prof;
    private Enseignant remplacant;
    private Matiere matiere;
    private Classe classe;
    private Salle salle;
    private String cle;
    private long n;

    @Test
    void uneSalleInconnueDuFichier_estSignaleeAvantEtApresLImport() throws Exception {
        String date = LocalDate.now().plusDays(5).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String csv = "DATE;HEURE_DEBUT;HEURE_FIN;MATRICULE_ENSEIGNANT;MATIERE;CLASSE;SALLE\r\n"
                + date + ";08:00;10:00;" + prof.getMatricule() + ";" + matiere.getLibelle() + ";" + classe.getLibelle()
                + ";Z99\r\n";
        MockMultipartFile fichier = new MockMultipartFile("fichier", "edt.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        long avant = salles();

        mockMvc.perform(multipart("/api/agent/planning/importer").file(fichier).param("simulation", "true")
                        .header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("1 élément(s) seraient ajoutés aux référentiels")))
                .andExpect(jsonPath("$.data.nouveauxReferentiels[0]").value("salle « Z99 »"));
        assertThat(salles()).isEqualTo(avant);

        mockMvc.perform(multipart("/api/agent/planning/importer").file(fichier).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("1 élément(s) ajouté(s) aux référentiels")))
                .andExpect(jsonPath("$.data.nouveauxReferentiels[0]").value("salle « Z99 »"));
        assertThat(salles()).isEqualTo(avant + 1);
    }

    @BeforeEach
    void preparer() {
        n = System.nanoTime() % 1_000_000;
        Role roleAdmin = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        etab = etablissementRepository.save(Etablissement.builder()
                .nom("Etab Ref").slug("agent-ref-" + n + System.nanoTime()).plan(Plan.ENTERPRISE).build());
        admin = userRepository.save(User.builder().email("admin-ref-" + System.nanoTime() + "@test.local")
                .password("{noop}x").roles(new HashSet<>(Set.of(roleAdmin))).etablissement(etab).build());
        prof = enseignant("P" + n, "Bamba");
        remplacant = enseignant("R" + n, "Kone");
        matiere = matiereRepository.save(tenant(Matiere.builder().libelle("Algebre" + n).build()));
        classe = classeRepository.save(tenant(Classe.builder().libelle("L1-" + n).build()));
        salle = salleRepository.save(tenant(Salle.builder().libelle("S" + n).build()));
        cle = cleAgentService.emettre(admin, "JEFF");
    }

    private <T extends ci.esatic.sigep.tenant.TenantScoped> T tenant(T entite) {
        entite.setEtablissementId(etab.getId());
        return entite;
    }

    private Enseignant enseignant(String matricule, String nom) {
        return enseignantRepository.save(tenant(Enseignant.builder().matricule(matricule).nom(nom).prenom("X")
                .statut(StatutEnseignant.VALIDATED).build()));
    }

    private Seance seance(Enseignant e, LocalDate date) {
        return seanceRepository.save(tenant(Seance.builder().date(date).heureDebut(LocalTime.of(8, 0))
                .heureFin(LocalTime.of(10, 0)).matiere(matiere).classe(classe).salle(salle).enseignant(e)
                .statut(StatutSeance.A_FAIRE).build()));
    }

    @AfterEach
    void nettoyer() {
        Long id = etab.getId();
        journalAgentRepository.deleteAll(journalAgentRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        cleAgentRepository.deleteAll(cleAgentRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        seanceRepository.deleteAll(seanceRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        periodeRepository.deleteAll(periodeRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        enseignantRepository.deleteAll(enseignantRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        salleRepository.deleteAll(salleRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        classeRepository.deleteAll(classeRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        matiereRepository.deleteAll(matiereRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        campusRepository.deleteAll(campusRepository.findAll().stream().filter(x -> id.equals(x.getEtablissementId())).toList());
        userRepository.deleteById(admin.getId());
        etablissementRepository.deleteById(id);
    }

    private long salles() {
        return salleRepository.findAll().stream().filter(s -> etab.getId().equals(s.getEtablissementId())).count();
    }

    @Test
    void ajouterUneSalle_simulationSansTrace_puisPourDeVrai() throws Exception {
        String corps = "{\"libelle\":\"b" + n + "\",\"batiment\":\"Bât. A\"}";
        mockMvc.perform(post("/api/agent/referentiels/salle").param("simulation", "true").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("serait ajouté")));
        assertThat(salles()).isEqualTo(1);

        mockMvc.perform(post("/api/agent/referentiels/salle").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isOk());
        assertThat(salles()).isEqualTo(2);

        // Les règles de la console : doublon et nom avec espace refusés.
        mockMvc.perform(post("/api/agent/referentiels/salle").param("simulation", "true").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("EXISTE_DEJA"));
        mockMvc.perform(post("/api/agent/referentiels/salle").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"libelle\":\"Salle B\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("NOM_INVALIDE"));
    }

    @Test
    void uneSalleUtilisee_nePeutEtreSupprimee_etLaSimulationLeDit() throws Exception {
        seance(prof, LocalDate.now().plusDays(1));
        String url = "/api/agent/referentiels/salle/" + salle.getId() + "/supprimer";
        mockMvc.perform(post(url).param("simulation", "true").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("UTILISE"));
        mockMvc.perform(post(url).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("UTILISE"));
        assertThat(salleRepository.findById(salle.getId())).isPresent();
    }

    @Test
    void uneClasseLibre_estSupprimee_maisPasEnSimulation() throws Exception {
        String url = "/api/agent/referentiels/classe/" + classe.getId() + "/supprimer";
        mockMvc.perform(post(url).param("simulation", "true").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk());
        assertThat(classeRepository.findById(classe.getId())).isPresent();
        mockMvc.perform(post(url).header(AgentAuthFilter.ENTETE, cle)).andExpect(status().isOk());
        assertThat(classeRepository.findById(classe.getId())).isEmpty();
    }

    @Test
    void uneFermeture_ditCombienDeSeancesPartiraient() throws Exception {
        LocalDate jour = LocalDate.now().plusDays(10);
        Seance s = seance(prof, jour);
        String corps = "{\"type\":\"FERIE\",\"libelle\":\"Fête\",\"debut\":\"" + jour + "\",\"retirerSeances\":true}";
        mockMvc.perform(post("/api/agent/calendrier/periodes").param("simulation", "true").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.seancesRetirees").value(1));
        assertThat(seanceRepository.findById(s.getId())).isPresent();
        assertThat(periodeRepository.findAll()).noneMatch(p -> etab.getId().equals(p.getEtablissementId()));

        mockMvc.perform(post("/api/agent/calendrier/periodes").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.seancesRetirees").value(1));
        assertThat(seanceRepository.findById(s.getId())).isEmpty();

        mockMvc.perform(get("/api/agent/calendrier").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.periodes[0].libelle").value("Fête"));
    }

    @Test
    void laTolerance_resteBornee() throws Exception {
        mockMvc.perform(post("/api/agent/calendrier/tolerance").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"avant\":10,\"apres\":500}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/agent/calendrier/tolerance").param("simulation", "true").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"avant\":5,\"apres\":20}"))
                .andExpect(status().isOk());
        assertThat(etablissementRepository.findById(etab.getId()).orElseThrow().getToleranceApresMinutes()).isEqualTo(30);
        mockMvc.perform(post("/api/agent/calendrier/tolerance").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"avant\":5,\"apres\":20}"))
                .andExpect(status().isOk());
        assertThat(etablissementRepository.findById(etab.getId()).orElseThrow().getToleranceApresMinutes()).isEqualTo(20);
    }

    @Test
    void unRemplacement_simuleNeChangeRienNiNEcrit() throws Exception {
        LocalDate jour = LocalDate.now().plusDays(3);
        Seance s = seance(prof, jour);
        String corps = "{\"absentId\":%d,\"remplacantId\":%d,\"debut\":\"%s\",\"fin\":\"%s\"}"
                .formatted(prof.getId(), remplacant.getId(), jour, jour);
        mockMvc.perform(post("/api/agent/calendrier/remplacement").param("simulation", "true").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.remplacees").value(1));
        assertThat(seanceRepository.findById(s.getId()).orElseThrow().getEnseignant().getId()).isEqualTo(prof.getId());

        mockMvc.perform(post("/api/agent/calendrier/remplacement").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.remplacees").value(1));
        Seance apres = seanceRepository.findById(s.getId()).orElseThrow();
        assertThat(apres.getEnseignant().getId()).isEqualTo(remplacant.getId());
        assertThat(apres.getEnseignantRemplace().getId()).isEqualTo(prof.getId());
    }

    @Test
    void importerUnEmploiDuTemps_simulationPuisPourDeVrai() throws Exception {
        String date = LocalDate.now().plusDays(4).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String csv = "DATE;HEURE_DEBUT;HEURE_FIN;MATRICULE_ENSEIGNANT;MATIERE;CLASSE;SALLE\r\n"
                + date + ";08:00;10:00;" + prof.getMatricule() + ";" + matiere.getLibelle() + ";" + classe.getLibelle()
                + ";" + salle.getLibelle() + "\r\n";
        MockMultipartFile fichier = new MockMultipartFile("fichier", "edt.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/agent/planning/importer").file(fichier).param("simulation", "true")
                        .header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.importees").value(1));
        assertThat(seanceRepository.findAll()).noneMatch(x -> etab.getId().equals(x.getEtablissementId()));

        mockMvc.perform(multipart("/api/agent/planning/importer").file(fichier).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.importees").value(1));
        assertThat(seanceRepository.findAll()).anyMatch(x -> etab.getId().equals(x.getEtablissementId()));

        MockMultipartFile pdf = new MockMultipartFile("fichier", "edt.pdf", "application/pdf", new byte[]{1, 2});
        mockMvc.perform(multipart("/api/agent/planning/importer").file(pdf).header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("FORMAT_NON_PRIS"));
    }

    @Test
    void leFormatDEmploiDuTemps_estControle() throws Exception {
        mockMvc.perform(post("/api/agent/planning/format").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"FANTAISIE\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/agent/planning/format").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"secondaire\"}"))
                .andExpect(status().isOk());
        assertThat(etablissementRepository.findById(etab.getId()).orElseThrow().getFormatEmploiDuTemps()).isEqualTo("SECONDAIRE");
    }
}
