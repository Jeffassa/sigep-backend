package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import ci.esatic.sigep.service.PlanningIaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Un document que les règles ne comprennent pas passe par l'IA, puis par la relecture humaine. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LecturePlanningIaTest {

    private static final byte[] PDF = "%PDF-1.7 emploi du temps".getBytes(StandardCharsets.UTF_8);

    @MockitoBean private DataInitializer dataInitializer;
    @MockitoBean private PlanningIaService ia;

    @Autowired private MockMvc mockMvc;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private SeanceRepository seanceRepository;

    private Etablissement ecole;
    private User admin;
    private Enseignant aya;

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        ecole = etablissementRepository.save(Etablissement.builder().nom("Ecole").slug("ia-" + n)
                .plan(Plan.ENTERPRISE).kioskKey("cle-" + n).build());
        Role role = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        admin = userRepository.save(User.builder().email("ia-" + n + "@test.local").password("x")
                .roles(new HashSet<>(Set.of(role))).etablissement(ecole).build());
        aya = enseignantRepository.save(Enseignant.builder().matricule("ENS-" + n).nom("Kouassi").prenom("Aya")
                .statut(StatutEnseignant.VALIDATED).etablissementId(ecole.getId()).build());
        Mockito.when(ia.disponible()).thenReturn(true);
    }

    private PlanningIaService.Extraction lu(String matricule, String enseignant) {
        return new PlanningIaService.Extraction(null, null, null, List.of(new PlanningIaService.Ligne(
                "2026-11-10", null, null, "08:00", "10:00", "Réseaux", matricule, enseignant, "L3", "B12", "Année déduite")));
    }

    private org.springframework.test.web.servlet.ResultActions deposer(String nom, byte[] octets) throws Exception {
        return mockMvc.perform(multipart("/admin/planning/importer")
                .file(new MockMultipartFile("fichier", nom, "application/octet-stream", octets))
                .with(user(admin)).with(csrf()));
    }

    @Test
    void unPdf_estLu_etRienNestEnregistreAvantLaRelecture() throws Exception {
        Mockito.when(ia.lire(any(), eq(PlanningIaService.TypeDocument.PDF), any(), any()))
                .thenReturn(lu(aya.getMatricule(), "A. Kouassi"));
        long avant = seanceRepository.count();

        deposer("edt.pdf", PDF).andExpect(status().isOk())
                .andExpect(view().name("admin/planning-relecture"))
                .andExpect(content().string(containsString("Réseaux")))
                .andExpect(content().string(containsString("Année déduite")));

        assertThat(seanceRepository.count()).isEqualTo(avant);
    }

    @Test
    void lesReperesDeLEtablissement_sontTransmis_sansAdresseEmail() throws Exception {
        Mockito.when(ia.lire(any(), any(), any(), any())).thenReturn(lu(null, null));
        deposer("edt.pdf", PDF);

        ArgumentCaptor<String> contexte = ArgumentCaptor.forClass(String.class);
        Mockito.verify(ia).lire(any(), any(), contexte.capture(), eq(ecole.getId()));
        assertThat(contexte.getValue()).contains("Aya Kouassi").doesNotContain("@");
    }

    @Test
    void unFichierDeguise_nePartPasALIa() throws Exception {
        deposer("edt.pdf", "ceci n'est pas un pdf".getBytes(StandardCharsets.UTF_8))
                .andExpect(redirectedUrl("/admin/rapports"));
        Mockito.verify(ia, Mockito.never()).lire(any(), any(), any(), any());
    }

    @Test
    void unTableurIncompris_estLuParLIa() throws Exception {
        Mockito.when(ia.lire(any(), eq(PlanningIaService.TypeDocument.TEXTE), any(), any())).thenReturn(lu(null, "Aya Kouassi"));
        deposer("edt.csv", "Notes diverses\nrien de lisible ici\n".getBytes(StandardCharsets.UTF_8))
                .andExpect(view().name("admin/planning-relecture"));
    }

    @Test
    void sansIaActivee_unPdfEstExplique() throws Exception {
        Mockito.when(ia.lire(any(), any(), any(), any()))
                .thenThrow(new PlanningIaService.LectureImpossible("La lecture intelligente n'est pas activée sur cette plateforme."));
        deposer("edt.pdf", PDF).andExpect(flash().attribute("error", containsString("pas activée")));
    }

    private org.springframework.test.web.servlet.ResultActions confirmer(String json) throws Exception {
        return mockMvc.perform(post("/admin/planning/confirmer").param("lignes", json)
                .param("debut", "2026-11-02").param("fin", "2026-11-22").param("semaineA", "2026-11-02")
                .with(user(admin)).with(csrf()));
    }

    private String ligne(Object enseignantId, String jour, String semaine) {
        return "{\"jour\":\"" + jour + "\",\"semaine\":\"" + semaine + "\",\"debut\":\"08:00\",\"fin\":\"10:00\","
                + "\"matiere\":\"Réseaux\",\"enseignantId\":" + enseignantId + ",\"classe\":\"L3\",\"salle\":\"B12\"}";
    }

    @Test
    void lesSeancesRelues_sEnregistrent_avecLAlternanceDesSemaines() throws Exception {
        confirmer("[" + ligne(aya.getId(), "LUNDI", "A") + "]").andExpect(redirectedUrl("/admin/rapports"));

        // Semaines A : 2 et 16 novembre ; le 9 est une semaine B.
        assertThat(seanceRepository.findByDateBetweenOrderByDateAscHeureDebutAsc(
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 22)))
                .filteredOn(s -> aya.getId().equals(s.getEnseignant().getId()))
                .extracting(Seance::getDate)
                .containsExactly(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 16));
    }

    @Test
    void uneLigneSansEnseignantChoisi_nEstPasEnregistree() throws Exception {
        long avant = seanceRepository.count();
        confirmer("[" + ligne("null", "LUNDI", "") + "]")
                .andExpect(flash().attribute("erreursImport", List.of("Ligne 1 : choisissez l'enseignant")));
        assertThat(seanceRepository.count()).isEqualTo(avant);
    }

    @Test
    void unEnvoiIllisible_nEnregistreRien() throws Exception {
        long avant = seanceRepository.count();
        confirmer("pas du json").andExpect(flash().attribute("error", containsString("Recommencez")));
        assertThat(seanceRepository.count()).isEqualTo(avant);
    }
}
