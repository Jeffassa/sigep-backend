package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import ci.esatic.sigep.security.JwtService;
import ci.esatic.sigep.service.CalendrierService;
import ci.esatic.sigep.service.StatistiquesService;
import ci.esatic.sigep.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Profils d'administration, compte partagé entre établissements, calendrier et remplacements. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConfortEtablissementsTest {

    private static final String MDP = "Secret@2026";

    @MockitoBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private SeanceRepository seanceRepository;
    @Autowired private MatiereRepository matiereRepository;
    @Autowired private ClasseRepository classeRepository;
    @Autowired private SalleRepository salleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private CalendrierService calendrierService;
    @Autowired private StatistiquesService statistiquesService;

    private Etablissement ecoleA;
    private Etablissement ecoleB;
    private long n;

    @BeforeEach
    void setUp() {
        n = System.nanoTime();
        ecoleA = etablissement("A");
        ecoleB = etablissement("B");
    }

    @AfterEach
    void nettoyer() {
        TenantContext.clear();
    }

    private Etablissement etablissement(String nom) {
        return etablissementRepository.save(Etablissement.builder().nom("Ecole " + nom).slug(nom + "-" + n)
                .plan(Plan.ENTERPRISE).kioskKey("cle-" + nom + "-" + n).build());
    }

    private User compte(ERole eRole, Etablissement ecole) {
        Role role = roleRepository.findByName(eRole).orElseGet(() -> roleRepository.save(new Role(null, eRole)));
        return userRepository.save(User.builder().email(eRole.name().toLowerCase() + "-" + System.nanoTime() + "@test.local")
                .password(passwordEncoder.encode(MDP)).roles(new HashSet<>(Set.of(role))).etablissement(ecole).build());
    }

    private Enseignant fiche(User compte, Etablissement ecole, String nom, StatutEnseignant statut) {
        return enseignantRepository.save(Enseignant.builder().matricule(nom + "-" + System.nanoTime()).nom(nom).prenom("Awa")
                .statut(statut).user(compte).etablissementId(ecole.getId()).build());
    }

    private org.springframework.test.web.servlet.ResultActions connexion(User compte) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", compte.getEmail(), "password", MDP))));
    }

    // ─── Profils ────────────────────────────────────────────────────────────

    private void verifierAcces(ERole profil, String chemin, boolean ouvert) throws Exception {
        var reponse = mockMvc.perform(get(chemin).with(user(compte(profil, ecoleA))));
        if (ouvert) {
            reponse.andExpect(status().isOk());
        } else {
            reponse.andExpect(status().isForbidden());
        }
    }

    @Test
    void chaqueProfil_nOuvreQueSesPages() throws Exception {
        verifierAcces(ERole.ROLE_DIRECTION, "/admin/statistiques", true);
        verifierAcces(ERole.ROLE_DIRECTION, "/admin/paie", true);
        verifierAcces(ERole.ROLE_DIRECTION, "/admin/calendrier", true);
        verifierAcces(ERole.ROLE_DIRECTION, "/admin/enseignants/nouveau", false);
        verifierAcces(ERole.ROLE_DIRECTION, "/admin/equipe", false);

        verifierAcces(ERole.ROLE_SCOLARITE, "/admin/enseignants/nouveau", true);
        verifierAcces(ERole.ROLE_SCOLARITE, "/admin/calendrier", true);
        verifierAcces(ERole.ROLE_SCOLARITE, "/admin/paie", false);
        verifierAcces(ERole.ROLE_SCOLARITE, "/admin/api", false);
        verifierAcces(ERole.ROLE_SCOLARITE, "/admin/equipe", false);

        verifierAcces(ERole.ROLE_COMPTABILITE, "/admin/paie", true);
        verifierAcces(ERole.ROLE_COMPTABILITE, "/admin/abonnement", true);
        verifierAcces(ERole.ROLE_COMPTABILITE, "/admin/enseignants", false);
        verifierAcces(ERole.ROLE_COMPTABILITE, "/admin/calendrier", false);

        verifierAcces(ERole.ROLE_ENSEIGNANT, "/admin/dashboard", false);
    }

    @Test
    void laDirection_neModifieRien() throws Exception {
        User direction = compte(ERole.ROLE_DIRECTION, ecoleA);
        for (String cible : new String[] {"/admin/calendrier/tolerance", "/admin/matieres", "/admin/enseignants",
                "/admin/planning/importer", "/admin/equipe", "/admin/abonnement/payer"}) {
            mockMvc.perform(post(cible).with(user(direction)).with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void leMenu_neProposeQueCeQueLeProfilPeutOuvrir() throws Exception {
        String direction = mockMvc.perform(get("/admin/calendrier").with(user(compte(ERole.ROLE_DIRECTION, ecoleA))))
                .andReturn().getResponse().getContentAsString();
        assertThat(direction).contains("main form[method=\"post\"]:not(.ouvert-lecture)")
                .contains("href=\"/admin/paie\"").doesNotContain("href=\"/admin/equipe\"");

        String compta = mockMvc.perform(get("/admin/paie").with(user(compte(ERole.ROLE_COMPTABILITE, ecoleA))))
                .andReturn().getResponse().getContentAsString();
        assertThat(compta).doesNotContain("href=\"/admin/enseignants\"").doesNotContain("href=\"/admin/calendrier\"");
    }

    @Test
    void lesProfilsSecondaires_neRecoiventPasDeJeton() throws Exception {
        connexion(compte(ERole.ROLE_SCOLARITE, ecoleA)).andExpect(status().isForbidden());
    }

    @Test
    void unMotDePasseProvisoire_seRemplaceAvantTout() throws Exception {
        User scolarite = compte(ERole.ROLE_SCOLARITE, ecoleA);
        scolarite.setMustChangePassword(true);
        userRepository.saveAndFlush(scolarite);
        mockMvc.perform(get("/admin/dashboard").with(user(scolarite)))
                .andExpect(redirectedUrl("/admin/mon-compte"));
        mockMvc.perform(get("/admin/mon-compte").with(user(scolarite))).andExpect(status().isOk());
    }

    @Test
    void unAccesRetire_fermeLaSessionOuverte() throws Exception {
        User admin = compte(ERole.ROLE_ADMIN, ecoleA);
        User scolarite = compte(ERole.ROLE_SCOLARITE, ecoleA);
        mockMvc.perform(post("/admin/equipe/{id}/acces", scolarite.getId()).param("actif", "false")
                .with(user(admin)).with(csrf())).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/admin/dashboard").with(user(scolarite)))
                .andExpect(redirectedUrl("/admin-login?logout=true"));
    }

    @Test
    void unAdministrateur_neTouchePasAuxComptesDUnAutreEtablissement() throws Exception {
        User admin = compte(ERole.ROLE_ADMIN, ecoleA);
        User ailleurs = compte(ERole.ROLE_SCOLARITE, ecoleB);
        mockMvc.perform(post("/admin/equipe/{id}/acces", ailleurs.getId()).param("actif", "false")
                .with(user(admin)).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(ailleurs.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void lAdministrateur_creeUnAccesDansSonEtablissement() throws Exception {
        User admin = compte(ERole.ROLE_ADMIN, ecoleA);
        roleRepository.findByName(ERole.ROLE_COMPTABILITE)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_COMPTABILITE)));
        String email = "nouvelle-" + n + "@test.local";
        mockMvc.perform(post("/admin/equipe").param("email", email).param("profil", "ROLE_COMPTABILITE")
                .with(user(admin)).with(csrf())).andExpect(status().is3xxRedirection());
        User cree = userRepository.findByEmail(email).orElseThrow();
        assertThat(cree.getEtablissement().getId()).isEqualTo(ecoleA.getId());
        assertThat(cree.isMustChangePassword()).isTrue();
        assertThat(cree.getRoles()).extracting(Role::getName).containsExactly(ERole.ROLE_COMPTABILITE);
    }

    @Test
    void onNePeutPasDonnerLeRoleAdministrateurDepuisLEquipe() throws Exception {
        User admin = compte(ERole.ROLE_ADMIN, ecoleA);
        String email = "pirate-" + n + "@test.local";
        mockMvc.perform(post("/admin/equipe").param("email", email).param("profil", "ROLE_SUPER_ADMIN")
                .with(user(admin)).with(csrf()));
        assertThat(userRepository.existsByEmail(email)).isFalse();
    }

    // ─── Compte partagé entre établissements ────────────────────────────────

    @Test
    void unVacataire_voitSesDeuxEtablissements_etPasse_delUnALAutre() throws Exception {
        User vacataire = compte(ERole.ROLE_ENSEIGNANT, ecoleA);
        fiche(vacataire, ecoleA, "Kone", StatutEnseignant.VALIDATED);
        fiche(vacataire, ecoleB, "Kone", StatutEnseignant.VALIDATED);

        connexion(vacataire).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.etablissementId").value(ecoleA.getId()))
                .andExpect(jsonPath("$.data.etablissements", hasSize(2)));

        String jeton = jwtService.generateToken(vacataire);
        mockMvc.perform(post("/api/profil/etablissement/{id}", ecoleB.getId()).header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.etablissementId").value(ecoleB.getId()))
                .andExpect(jsonPath("$.data.etablissementNom").value("Ecole B"));
        assertThat(userRepository.findById(vacataire.getId()).orElseThrow().getEtablissement().getId())
                .isEqualTo(ecoleB.getId());
    }

    @Test
    void personneNEntreDansUnEtablissementOuIlNaPasDeFiche() throws Exception {
        User enseignant = compte(ERole.ROLE_ENSEIGNANT, ecoleA);
        fiche(enseignant, ecoleA, "Kone", StatutEnseignant.VALIDATED);
        String jeton = jwtService.generateToken(enseignant);

        mockMvc.perform(post("/api/profil/etablissement/{id}", ecoleB.getId()).header("Authorization", "Bearer " + jeton))
                .andExpect(status().is4xxClientError());
        assertThat(userRepository.findById(enseignant.getId()).orElseThrow().getEtablissement().getId())
                .isEqualTo(ecoleA.getId());
    }

    @Test
    void uneFicheNonValidee_neDonnePasAccesASonEtablissement() throws Exception {
        User vacataire = compte(ERole.ROLE_ENSEIGNANT, ecoleA);
        fiche(vacataire, ecoleA, "Kone", StatutEnseignant.VALIDATED);
        fiche(vacataire, ecoleB, "Kone", StatutEnseignant.ARCHIVE);
        String jeton = jwtService.generateToken(vacataire);

        mockMvc.perform(post("/api/profil/etablissement/{id}", ecoleB.getId()).header("Authorization", "Bearer " + jeton))
                .andExpect(status().isForbidden());
    }

    @Test
    void siSonEtablissementFerme_leVacataireTravailleDansLAutre() throws Exception {
        User vacataire = compte(ERole.ROLE_ENSEIGNANT, ecoleA);
        fiche(vacataire, ecoleA, "Kone", StatutEnseignant.VALIDATED);
        fiche(vacataire, ecoleB, "Kone", StatutEnseignant.VALIDATED);
        ecoleA.setActif(false);
        etablissementRepository.saveAndFlush(ecoleA);

        connexion(vacataire).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.etablissementId").value(ecoleB.getId()))
                .andExpect(jsonPath("$.data.etablissements", hasSize(1)));
    }

    @Test
    void ajouterUneAdresseConnue_rattacheSansChangerLeMotDePasse() throws Exception {
        User vacataire = compte(ERole.ROLE_ENSEIGNANT, ecoleA);
        fiche(vacataire, ecoleA, "Kone", StatutEnseignant.VALIDATED);
        String empreinte = vacataire.getPassword();
        User adminB = compte(ERole.ROLE_ADMIN, ecoleB);

        mockMvc.perform(post("/admin/enseignants").with(user(adminB)).with(csrf())
                        .param("matricule", "B-" + n).param("nom", "Kone").param("prenom", "Awa")
                        .param("email", vacataire.getEmail()))
                .andExpect(redirectedUrl("/admin/enseignants"));

        assertThat(enseignantRepository.fichesDuCompte(vacataire.getId())).hasSize(2);
        User relu = userRepository.findById(vacataire.getId()).orElseThrow();
        assertThat(relu.getPassword()).isEqualTo(empreinte);
        assertThat(relu.getEtablissement().getId()).isEqualTo(ecoleA.getId());
    }

    @Test
    void uneAdresseDAdministrateur_neDevientPasUneFicheEnseignant() throws Exception {
        User adminA = compte(ERole.ROLE_ADMIN, ecoleA);
        User adminB = compte(ERole.ROLE_ADMIN, ecoleB);

        mockMvc.perform(post("/admin/enseignants").with(user(adminB)).with(csrf())
                        .param("matricule", "B-" + n).param("nom", "X").param("prenom", "Y")
                        .param("email", adminA.getEmail()))
                .andExpect(redirectedUrl("/admin/enseignants/nouveau"));

        assertThat(enseignantRepository.fichesDuCompte(adminA.getId())).isEmpty();
    }

    @Test
    void lAutreEtablissement_neRenvoiePasDAccesSurUnComptePartage() throws Exception {
        User vacataire = compte(ERole.ROLE_ENSEIGNANT, ecoleA);
        fiche(vacataire, ecoleA, "Kone", StatutEnseignant.VALIDATED);
        Enseignant ficheB = fiche(vacataire, ecoleB, "Kone", StatutEnseignant.VALIDATED);
        String empreinte = vacataire.getPassword();

        mockMvc.perform(post("/admin/enseignants/{id}/renvoyer-acces", ficheB.getId())
                .with(user(compte(ERole.ROLE_ADMIN, ecoleB))).with(csrf()));

        assertThat(userRepository.findById(vacataire.getId()).orElseThrow().getPassword()).isEqualTo(empreinte);
    }

    @Test
    void supprimerUneFiche_garde_leCompteUtiliseAilleurs() throws Exception {
        User vacataire = compte(ERole.ROLE_ENSEIGNANT, ecoleA);
        fiche(vacataire, ecoleA, "Kone", StatutEnseignant.VALIDATED);
        Enseignant ficheB = fiche(vacataire, ecoleB, "Kone", StatutEnseignant.VALIDATED);

        mockMvc.perform(post("/admin/enseignants/{id}/supprimer", ficheB.getId())
                .with(user(compte(ERole.ROLE_ADMIN, ecoleB))).with(csrf()));

        assertThat(userRepository.findById(vacataire.getId())).isPresent();
        assertThat(enseignantRepository.fichesDuCompte(vacataire.getId())).hasSize(1);
    }

    // ─── Calendrier, remplacement, heures réelles ───────────────────────────

    private Seance seance(Enseignant enseignant, LocalDate date, LocalTime debut, LocalTime fin, StatutSeance statut) {
        return seanceRepository.saveAndFlush(Seance.builder().date(date).heureDebut(debut).heureFin(fin)
                .enseignant(enseignant)
                .matiere(matiereRepository.save(Matiere.builder().libelle("M" + System.nanoTime()).etablissementId(ecoleA.getId()).build()))
                .classe(classeRepository.save(Classe.builder().libelle("C" + System.nanoTime()).etablissementId(ecoleA.getId()).build()))
                .salle(salleRepository.save(Salle.builder().libelle("S" + System.nanoTime()).etablissementId(ecoleA.getId()).build()))
                .statut(statut).etablissementId(ecoleA.getId()).build());
    }

    @Test
    void lesHeures_suiventLaDureeReelleDesSeances() {
        LocalDate jour = LocalDate.of(2026, 11, 3);
        Enseignant e = fiche(compte(ERole.ROLE_ENSEIGNANT, ecoleA), ecoleA, "Kone", StatutEnseignant.VALIDATED);
        seance(e, jour, LocalTime.of(8, 0), LocalTime.of(9, 30), StatutSeance.EMARGE);
        seance(e, jour, LocalTime.of(10, 0), LocalTime.of(13, 0), StatutSeance.EMARGE);
        seance(e, jour, LocalTime.of(14, 0), LocalTime.of(16, 0), StatutSeance.A_FAIRE);

        Map<String, Object> stats = statistiquesService.computeStatistiques(jour, jour);

        assertThat(stats.get("heures")).isEqualTo(4.5);
    }

    @Test
    void uneFermeture_retireLesSeancesNonEmargees_seulement() {
        TenantContext.set(ecoleA.getId());
        LocalDate jour = LocalDate.of(2026, 12, 25);
        Enseignant e = fiche(compte(ERole.ROLE_ENSEIGNANT, ecoleA), ecoleA, "Kone", StatutEnseignant.VALIDATED);
        Seance aRetirer = seance(e, jour, LocalTime.of(8, 0), LocalTime.of(10, 0), StatutSeance.A_FAIRE);
        Seance faite = seance(e, jour, LocalTime.of(10, 0), LocalTime.of(12, 0), StatutSeance.EMARGE);

        int retirees = calendrierService.ajouterPeriode(TypePeriode.FERIE, "Noël", jour, null, true);

        assertThat(retirees).isEqualTo(1);
        assertThat(seanceRepository.findById(aRetirer.getId())).isEmpty();
        assertThat(seanceRepository.findById(faite.getId())).isPresent();
    }

    @Test
    void unRemplacement_gardeLEnseignantPrevu_etRespecteLesConflits() {
        TenantContext.set(ecoleA.getId());
        LocalDate jour = LocalDate.of(2026, 11, 3);
        Enseignant absent = fiche(compte(ERole.ROLE_ENSEIGNANT, ecoleA), ecoleA, "Absent", StatutEnseignant.VALIDATED);
        Enseignant remplacant = fiche(compte(ERole.ROLE_ENSEIGNANT, ecoleA), ecoleA, "Remplacant", StatutEnseignant.VALIDATED);
        Seance libre = seance(absent, jour, LocalTime.of(8, 0), LocalTime.of(10, 0), StatutSeance.A_FAIRE);
        Seance enConflit = seance(absent, jour, LocalTime.of(10, 0), LocalTime.of(12, 0), StatutSeance.A_FAIRE);
        Seance faite = seance(absent, jour, LocalTime.of(14, 0), LocalTime.of(16, 0), StatutSeance.EMARGE);
        seance(remplacant, jour, LocalTime.of(11, 0), LocalTime.of(13, 0), StatutSeance.A_FAIRE);

        CalendrierService.Remplacement r = calendrierService.remplacer(absent.getId(), remplacant.getId(), jour, jour);

        assertThat(r.remplacees()).isEqualTo(1);
        assertThat(r.conflits()).isEqualTo(1);
        assertThat(libre.getEnseignant().getId()).isEqualTo(remplacant.getId());
        assertThat(libre.getEnseignantRemplace().getId()).isEqualTo(absent.getId());
        assertThat(enConflit.getEnseignant().getId()).isEqualTo(absent.getId());
        assertThat(faite.getEnseignant().getId()).isEqualTo(absent.getId());
    }

    @Test
    void laTolerance_seRegleEtResteBornee() throws Exception {
        User admin = compte(ERole.ROLE_ADMIN, ecoleA);
        mockMvc.perform(post("/admin/calendrier/tolerance").param("avant", "5").param("apres", "45")
                .with(user(admin)).with(csrf())).andExpect(status().is3xxRedirection());
        Etablissement relu = etablissementRepository.findById(ecoleA.getId()).orElseThrow();
        assertThat(relu.getToleranceAvantMinutes()).isEqualTo(5);
        assertThat(relu.getToleranceApresMinutes()).isEqualTo(45);

        mockMvc.perform(post("/admin/calendrier/tolerance").param("avant", "500").param("apres", "45")
                .with(user(admin)).with(csrf()));
        assertThat(etablissementRepository.findById(ecoleA.getId()).orElseThrow().getToleranceAvantMinutes()).isEqualTo(5);
    }
}
