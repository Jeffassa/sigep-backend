package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import ci.esatic.sigep.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Isolation multi-tenant de bout en bout : un enseignant de l'établissement A ne doit JAMAIS
 * pouvoir lire une ressource de l'établissement B (chargement par id — cas non couvert par le
 * filtre Hibernate, rattrapé par le garde @PostLoad → 404). C'est LA propriété de sécurité
 * fondamentale du produit ; ce test la verrouille.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IsolationInterTenantIntegrationTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private MatiereRepository matiereRepository;
    @Autowired private ClasseRepository classeRepository;
    @Autowired private SalleRepository salleRepository;
    @Autowired private SeanceRepository seanceRepository;

    private String jwtA;
    private Long seanceIdA;
    private Long seanceIdB;

    @BeforeEach
    void setUp() {
        Role role = roleRepository.findByName(ERole.ROLE_ENSEIGNANT)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ENSEIGNANT)));

        Etablissement a = etablissementRepository.save(
                Etablissement.builder().nom("École A").slug("ecole-a").build());
        Etablissement b = etablissementRepository.save(
                Etablissement.builder().nom("École B").slug("ecole-b").build());

        // Utilisateur + enseignant du tenant A (celui qui fera les requêtes).
        User userA = userRepository.save(User.builder()
                .email("prof.a@ecole-a.ci").password(passwordEncoder.encode("Secret@2026"))
                .roles(Set.of(role)).etablissement(a).build());
        seanceIdA = seanceRepository.save(seance(a.getId(),
                enseignant(a.getId(), "ENS-A", userA))).getId();

        // Données du tenant B (enseignant sans compte suffit pour la séance).
        seanceIdB = seanceRepository.save(seance(b.getId(),
                enseignant(b.getId(), "ENS-B", null))).getId();

        jwtA = jwtService.generateToken(userA);
    }

    private Enseignant enseignant(Long etabId, String matricule, User user) {
        return enseignantRepository.save(Enseignant.builder()
                .matricule(matricule).nom("Nom").prenom("Prenom")
                .statut(StatutEnseignant.VALIDATED).user(user)
                .etablissementId(etabId).build());
    }

    private Seance seance(Long etabId, Enseignant ens) {
        Matiere m = matiereRepository.save(Matiere.builder().libelle("Matiere " + etabId).etablissementId(etabId).build());
        Classe c = classeRepository.save(Classe.builder().libelle("Classe " + etabId).etablissementId(etabId).build());
        Salle s = salleRepository.save(Salle.builder().libelle("Salle" + etabId).etablissementId(etabId).build());
        return Seance.builder()
                .date(LocalDate.now()).heureDebut(LocalTime.of(9, 0)).heureFin(LocalTime.of(11, 0))
                .matiere(m).classe(c).salle(s).enseignant(ens)
                .type(TypeSeance.NORMALE).statut(StatutSeance.A_FAIRE)
                .etablissementId(etabId).build();
    }

    @Test
    void enseignantA_peutLireSaPropreSeance() throws Exception {
        mockMvc.perform(get("/api/seances/" + seanceIdA)
                        .header("Authorization", "Bearer " + jwtA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(seanceIdA));
    }

    @Test
    void enseignantA_neVoitPasLaSeanceDuTenantB() throws Exception {
        // Isolation : la séance de B doit être INTROUVABLE pour A (404, pas 200).
        mockMvc.perform(get("/api/seances/" + seanceIdB)
                        .header("Authorization", "Bearer " + jwtA))
                .andExpect(status().isNotFound());
    }
}
