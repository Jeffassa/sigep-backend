package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.Classe;
import ci.esatic.sigep.entity.DemandeRattrapage;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Emargement;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Matiere;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.Salle;
import ci.esatic.sigep.entity.Seance;
import ci.esatic.sigep.entity.StatutDemande;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.StatutSeance;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.ClasseRepository;
import ci.esatic.sigep.repository.CleAgentRepository;
import ci.esatic.sigep.repository.DemandeRattrapageRepository;
import ci.esatic.sigep.repository.EmargementRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.JournalAgentRepository;
import ci.esatic.sigep.repository.MatiereRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API de l'agent, page Alertes : séances importées, rattrapages, émargements hors ligne,
 * planification. Mêmes services que la console ; la simulation n'écrit rien ; le lot ne valide
 * que les séances relues.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentSeanceTest {

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
    @Autowired private DemandeRattrapageRepository rattrapageRepository;
    @Autowired private EmargementRepository emargementRepository;
    @Autowired private CleAgentRepository cleAgentRepository;
    @Autowired private JournalAgentRepository journalAgentRepository;
    @Autowired private CleAgentService cleAgentService;

    private Etablissement etab;
    private User admin;
    private Enseignant enseignant;
    private Matiere matiere;
    private Classe classe;
    private Salle salle;
    private Seance importee1;
    private Seance importee2;
    private Seance emargeeHorsLigne;
    private DemandeRattrapage demande;
    private Emargement horsLigne;
    private String cle;

    @BeforeEach
    void preparer() {
        long n = System.nanoTime();
        Role roleAdmin = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        etab = etablissementRepository.save(Etablissement.builder()
                .nom("Etab Seances").slug("agent-seances-" + n).plan(Plan.ENTERPRISE).build());
        Long id = etab.getId();
        admin = userRepository.save(User.builder().email("admin-seances-" + n + "@test.local").password("{noop}x")
                .roles(new HashSet<>(Set.of(roleAdmin))).etablissement(etab).build());

        enseignant = Enseignant.builder().matricule("S-" + n).nom("Bamba").prenom("Ali")
                .statut(StatutEnseignant.VALIDATED).build();
        enseignant.setEtablissementId(id);
        enseignant = enseignantRepository.save(enseignant);

        matiere = Matiere.builder().libelle("Algèbre " + n).build();
        matiere.setEtablissementId(id);
        matiere = matiereRepository.save(matiere);
        classe = Classe.builder().libelle("L1 " + n).build();
        classe.setEtablissementId(id);
        classe = classeRepository.save(classe);
        salle = Salle.builder().libelle("B12 " + n).build();
        salle.setEtablissementId(id);
        salle = salleRepository.save(salle);

        importee1 = seance(LocalDate.now().plusDays(1), StatutSeance.A_VALIDER);
        importee2 = seance(LocalDate.now().plusDays(2), StatutSeance.A_VALIDER);
        emargeeHorsLigne = seance(LocalDate.now().minusDays(1), StatutSeance.EN_ATTENTE_VALIDATION);

        Emargement e = Emargement.builder().seance(emargeeHorsLigne).enseignant(enseignant)
                .dateHeure(LocalDateTime.now().minusDays(1)).horsLigne(true).valide(false).build();
        e.setEtablissementId(id);
        horsLigne = emargementRepository.save(e);

        DemandeRattrapage d = DemandeRattrapage.builder().enseignant(enseignant).matiere(matiere).classe(classe)
                .dateSouhaitee(LocalDate.now().plusDays(5)).heureSouhaitee(LocalTime.of(10, 0))
                .motif("Grève").dateCreation(LocalDateTime.now()).build();
        d.setEtablissementId(id);
        demande = rattrapageRepository.save(d);

        cle = cleAgentService.emettre(admin, "JEFF");
    }

    private Seance seance(LocalDate date, StatutSeance statut) {
        Seance s = Seance.builder().date(date).heureDebut(LocalTime.of(8, 0)).heureFin(LocalTime.of(10, 0))
                .matiere(matiere).classe(classe).salle(salle).enseignant(enseignant).statut(statut).build();
        s.setEtablissementId(etab.getId());
        return seanceRepository.save(s);
    }

    @AfterEach
    void nettoyer() {
        Long id = etab.getId();
        journalAgentRepository.deleteAll(journalAgentRepository.findAll().stream()
                .filter(j -> id.equals(j.getEtablissementId())).toList());
        cleAgentRepository.deleteAll(cleAgentRepository.findAll().stream()
                .filter(c -> id.equals(c.getEtablissementId())).toList());
        emargementRepository.deleteAll(emargementRepository.findAll().stream()
                .filter(x -> id.equals(x.getEtablissementId())).toList());
        rattrapageRepository.deleteAll(rattrapageRepository.findAll().stream()
                .filter(x -> id.equals(x.getEtablissementId())).toList());
        seanceRepository.deleteAll(seanceRepository.findAll().stream()
                .filter(x -> id.equals(x.getEtablissementId())).toList());
        enseignantRepository.delete(enseignant);
        salleRepository.delete(salle);
        classeRepository.delete(classe);
        matiereRepository.delete(matiere);
        userRepository.deleteById(admin.getId());
        etablissementRepository.deleteById(id);
    }

    private StatutSeance statut(Seance s) {
        return seanceRepository.findById(s.getId()).orElseThrow().getStatut();
    }

    @Test
    void lesAlertes_rassemblentCeQueMontreLaPage() throws Exception {
        mockMvc.perform(get("/api/agent/alertes").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nbSeancesAValider").value(2))
                .andExpect(jsonPath("$.data.nbRattrapagesEnAttente").value(1))
                .andExpect(jsonPath("$.data.nbEmargementsHorsLigne").value(1))
                .andExpect(jsonPath("$.data.rattrapagesEnAttente[0].motif").value("Grève"));
    }

    @Test
    void leLot_neValideQueLesSeancesRelues() throws Exception {
        // La simulation sans liste donne toutes les séances en attente, et ne change rien.
        mockMvc.perform(post("/api/agent/seances/valider").param("simulation", "true")
                        .header(AgentAuthFilter.ENTETE, cle).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ids", hasSize(2)));
        assertThat(statut(importee1)).isEqualTo(StatutSeance.A_VALIDER);

        // Pour de vrai, une liste est exigée : « tout » ne part jamais à l'aveugle.
        mockMvc.perform(post("/api/agent/seances/valider").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        // Une liste qui contient une séance déjà traitée : rien n'est fait.
        mockMvc.perform(post("/api/agent/seances/valider").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + importee1.getId() + "," + emargeeHorsLigne.getId() + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("ETAT_INVALIDE"));
        assertThat(statut(importee1)).isEqualTo(StatutSeance.A_VALIDER);

        mockMvc.perform(post("/api/agent/seances/valider").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + importee1.getId() + "," + importee2.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("2 séance(s) acceptée(s)")));
        assertThat(statut(importee1)).isEqualTo(StatutSeance.A_FAIRE);
        assertThat(statut(importee2)).isEqualTo(StatutSeance.A_FAIRE);
    }

    @Test
    void refuserUneSeanceImportee_laRetire() throws Exception {
        mockMvc.perform(post("/api/agent/seances/" + importee1.getId() + "/refuser").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk());
        assertThat(seanceRepository.findById(importee1.getId())).isEmpty();
    }

    @Test
    void accepterUnRattrapage_creeLaSeance_maisPasEnSimulation() throws Exception {
        String corps = "{\"salleId\":" + salle.getId() + "}";
        mockMvc.perform(post("/api/agent/rattrapages/" + demande.getId() + "/accepter").param("simulation", "true")
                        .header(AgentAuthFilter.ENTETE, cle).contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("serait accepté")));
        assertThat(rattrapageRepository.findById(demande.getId()).orElseThrow().getStatut())
                .isEqualTo(StatutDemande.EN_ATTENTE);
        verify(mailService, never()).notifierDecisionRattrapage(any(), any(), any(), any(), any(), anyBoolean());

        mockMvc.perform(post("/api/agent/rattrapages/" + demande.getId() + "/accepter")
                        .header(AgentAuthFilter.ENTETE, cle).contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isOk());
        DemandeRattrapage apres = rattrapageRepository.findById(demande.getId()).orElseThrow();
        assertThat(apres.getStatut()).isEqualTo(StatutDemande.ACCEPTE);
        assertThat(apres.getSeanceRattrapage()).isNotNull();

        // Déjà traitée : un second passage est refusé.
        mockMvc.perform(post("/api/agent/rattrapages/" + demande.getId() + "/refuser").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validerUnEmargementHorsLigne_compteLaSeance() throws Exception {
        mockMvc.perform(post("/api/agent/emargements-hors-ligne/" + horsLigne.getId() + "/valider")
                        .header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk());
        assertThat(emargementRepository.findById(horsLigne.getId()).orElseThrow().isValide()).isTrue();
        assertThat(statut(emargeeHorsLigne)).isEqualTo(StatutSeance.EMARGE);
    }

    @Test
    void planifierUneSeance_verifieLHoraire() throws Exception {
        String base = "{\"enseignantId\":%d,\"matiereId\":%d,\"classeId\":%d,\"salleId\":%d,"
                + "\"date\":\"%s\",\"heureDebut\":\"%s\",\"heureFin\":\"%s\"}";
        String date = LocalDate.now().plusDays(3).toString();

        mockMvc.perform(post("/api/agent/seances").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(base.formatted(enseignant.getId(), matiere.getId(), classe.getId(), salle.getId(),
                                date, "10:00", "08:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("HORAIRE_INVALIDE"));

        long avant = seanceRepository.count();
        mockMvc.perform(post("/api/agent/seances").param("simulation", "true").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(base.formatted(enseignant.getId(), matiere.getId(), classe.getId(), salle.getId(),
                                date, "08:00", "10:00")))
                .andExpect(status().isOk());
        assertThat(seanceRepository.count()).isEqualTo(avant);

        mockMvc.perform(post("/api/agent/seances").header(AgentAuthFilter.ENTETE, cle)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(base.formatted(enseignant.getId(), matiere.getId(), classe.getId(), salle.getId(),
                                date, "08:00", "10:00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statut").value("A_FAIRE"));
        assertThat(seanceRepository.count()).isEqualTo(avant + 1);
    }

    @Test
    void lesReferentiels_etLesSeancesDuJour_sontLisibles() throws Exception {
        mockMvc.perform(get("/api/agent/referentiels").header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.salles[0].id").value(salle.getId()));
        mockMvc.perform(get("/api/agent/seances").param("date", importee1.getDate().toString())
                        .header(AgentAuthFilter.ENTETE, cle))
                .andExpect(status().isOk());
    }
}
