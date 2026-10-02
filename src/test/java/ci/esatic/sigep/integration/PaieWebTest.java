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
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.MatiereRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.SalleRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import ci.esatic.sigep.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * L'écran de paie et ses deux téléchargements, servis par la vraie chaîne de sécurité.
 *
 * <p><b>Pas de {@code @WithMockUser} ici.</b> Cette annotation pose le contexte de sécurité en
 * amont des filtres : une URL d'administration mal rattachée continuerait de passer sous son
 * couvert, et le défaut n'apparaîtrait qu'en production. On envoie donc une vraie session de
 * navigateur portant un vrai {@code User} — c'est aussi le seul principal dont
 * {@code EtablissementCourantService} sait tirer un établissement, et donc un plan.
 *
 * <p>L'enjeu propre à ces URL : un verrou d'offre masqué dans l'interface ne verrouille rien.
 * L'adresse du fichier est devinable, et le fichier, lui, contient tout le corps enseignant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PaieWebTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private SeanceRepository seanceRepository;
    @Autowired private MatiereRepository matiereRepository;
    @Autowired private ClasseRepository classeRepository;
    @Autowired private SalleRepository salleRepository;

    /** Dernier établissement créé par {@link #session}, pour y rattacher des données. */
    private Etablissement dernierEtablissement;

    /** Une session d'administrateur d'un établissement au plan demandé. */
    private MockHttpSession session(Plan plan) {
        Etablissement etablissement = etablissementRepository.save(Etablissement.builder()
                .nom("Ecole " + plan).slug("ecole-paie-web-" + System.nanoTime())
                .plan(plan).build());
        dernierEtablissement = etablissement;
        Role role = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        User admin = userRepository.save(User.builder()
                .email("admin.paie." + System.nanoTime() + "@ecole.ci")
                .password(passwordEncoder.encode("x"))
                .roles(Set.of(role)).etablissement(etablissement).build());

        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                admin, "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);
        return session;
    }

    @Test
    void lEcran_seRend_pourUnPlanQuiComprendLExport() throws Exception {
        // Le rendu réel du gabarit : une expression fautive ferait échouer ce test, alors
        // qu'elle passerait inaperçue à la compilation.
        var reponse = mockMvc.perform(get("/admin/paie?mois=2026-03").session(session(Plan.PRO)))
                .andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(200);
        String html = reponse.getContentAsString();
        assertThat(html).contains("Heures faites").contains("Mars 2026");
        assertThat(html).contains("Heures à payer");
        // La frontière du produit doit être écrite là où le fichier se produit.
        assertThat(html).contains("Aucun montant");
    }

    @Test
    void lEcran_expliqueLeVerrou_auPlanGratuit() throws Exception {
        var reponse = mockMvc.perform(get("/admin/paie").session(session(Plan.FREE)))
                .andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(200);
        assertThat(reponse.getContentAsString())
                .as("un verrou doit dire de quoi il s'agit, et ou lever la restriction")
                .contains("plans Pro et Enterprise").contains("/admin/abonnement");
    }

    @Test
    void lesFichiers_sontRefuses_auPlanGratuit() throws Exception {
        // Le bouton masqué ne suffit pas : l'adresse se devine, et ce fichier porte le corps
        // enseignant entier.
        for (String url : List.of("/admin/paie/export.csv", "/admin/paie/export.xlsx")) {
            MockHttpSession s = session(Plan.FREE);
            assertThat(mockMvc.perform(get(url).session(s)).andReturn().getResponse().getStatus())
                    .as("acces direct a " + url + " en plan gratuit")
                    .isEqualTo(403);
        }
    }

    @Test
    void leCsv_arriveCommeUnFichier_pasCommeUnePage() throws Exception {
        var reponse = mockMvc.perform(get("/admin/paie/export.csv?mois=2026-03")
                .session(session(Plan.PRO))).andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(200);
        assertThat(reponse.getHeader("Content-Disposition"))
                .startsWith("attachment; filename=\"paie_").endsWith("_2026-03.csv\"");
        assertThat(reponse.getContentType()).contains("text/csv");
        // Le BOM en tête : sans lui, Excel affiche les accents en charabia.
        assertThat(reponse.getContentAsByteArray()[0] & 0xFF).isEqualTo(0xEF);
    }

    @Test
    void lExcel_arriveCommeUnClasseur() throws Exception {
        var reponse = mockMvc.perform(get("/admin/paie/export.xlsx?mois=2026-03")
                .session(session(Plan.PRO))).andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(200);
        assertThat(reponse.getHeader("Content-Disposition"))
                .startsWith("attachment; filename=\"paie_").endsWith("_2026-03.xlsx\"");
        assertThat(reponse.getContentAsByteArray()).startsWith('P', 'K');   // en-tete ZIP d'un xlsx
    }

    @Test
    void unMoisIllisible_ramenAuMoisCourant_sansErreur() throws Exception {
        // Le paramètre vient d'une URL, et une URL se recopie mal. Une erreur 500 pour une
        // valeur de travers serait une panne là où une valeur par défaut suffit.
        var reponse = mockMvc.perform(get("/admin/paie?mois=n-importe-quoi")
                .session(session(Plan.PRO))).andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(200);
    }

    @Test
    void leNomDuFichier_neVientJamaisDeLUtilisateur() throws Exception {
        // Un retour chariot glissé dans un en-tête HTTP y injecte ce qu'on veut (CWE-113). Le
        // nom est reconstruit à partir du mois normalisé, jamais de la chaîne reçue : la
        // meilleure garantie reste qu'aucun texte de l'utilisateur n'y entre.
        var reponse = mockMvc.perform(get("/admin/paie/export.csv")
                        .param("mois", "2026-03\r\nX-Injecte: oui")
                        .session(session(Plan.PRO)))
                .andReturn().getResponse();

        String entete = reponse.getHeader("Content-Disposition");
        assertThat(entete).doesNotContain("X-Injecte").doesNotContain("\r").doesNotContain("\n");
        assertThat(reponse.getHeader("X-Injecte")).isNull();
    }

    /**
     * Un enseignant et deux séances rattachés au dernier établissement créé.
     *
     * <p>Sans données, {@code th:each} ne rend jamais le corps du tableau : une expression
     * fautive sur une ligne — un accesseur de record mal orthographié, une méthode qui n'existe
     * pas — passerait tous les tests et ne se verrait qu'en production, sur l'écran d'un
     * gestionnaire qui prépare une paie.
     */
    private Enseignant peupler(String matricule, String nom, String prenom) {
        Long t = dernierEtablissement.getId();
        Matiere m = Matiere.builder().libelle("Algorithmique").build();
        m.setEtablissementId(t);
        m = matiereRepository.save(m);
        Classe c = Classe.builder().libelle("L2 Info").build();
        c.setEtablissementId(t);
        c = classeRepository.save(c);
        Salle sa = Salle.builder().libelle("A101").build();
        sa.setEtablissementId(t);
        sa = salleRepository.save(sa);

        Enseignant e = Enseignant.builder().matricule(matricule).nom(nom).prenom(prenom)
                .grade("Vacataire").departement("Informatique")
                .statut(StatutEnseignant.VALIDATED).build();
        e.setEtablissementId(t);
        e = enseignantRepository.save(e);

        // Une séance payée, une séance sans émargement : la ligne n'est donc pas nette et
        // toutes les branches du gabarit sont exercées.
        seance(e, 3, StatutSeance.EMARGE, m, c, sa);
        seance(e, 4, StatutSeance.A_FAIRE, m, c, sa);
        return e;
    }

    private void seance(Enseignant e, int jour, StatutSeance statut,
                        Matiere m, Classe c, Salle sa) {
        Seance s = Seance.builder()
                .date(LocalDate.of(2026, 3, jour))
                .heureDebut(LocalTime.of(8, 0)).heureFin(LocalTime.of(10, 30))
                .matiere(m).classe(c).salle(sa).enseignant(e).statut(statut).build();
        s.setEtablissementId(dernierEtablissement.getId());
        seanceRepository.save(s);
    }

    @Test
    void leCorpsDuTableau_seRendVraiment() throws Exception {
        // LE test qui manquait. Les autres creaient un etablissement vide : la boucle th:each
        // ne s executait jamais, et aucune expression de ligne n etait donc eprouvee.
        MockHttpSession s = session(Plan.PRO);
        peupler("ENS-0142", "Kouassi", "Ameyo");

        String html = mockMvc.perform(get("/admin/paie?mois=2026-03").session(s))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("ENS-0142");
        assertThat(html).contains("Ameyo Kouassi");        // nomComplet()
        assertThat(html).contains("Vacataire");
        assertThat(html).contains("1 / 2");                 // seancesDues() au denominateur
        assertThat(html).contains("2,50");                  // heures de la seance emargee
        assertThat(html).contains("2,50 h");                // heuresAArbitrer() sur la pastille
    }

    @Test
    void unNomPorteurDeBalises_estEchappe_pasExecute() throws Exception {
        // Les noms viennent d imports fournis par l etablissement. Un nom d enseignant ne doit
        // jamais pouvoir poser un script sur l ecran de l administrateur.
        MockHttpSession s = session(Plan.PRO);
        peupler("ENS-XSS", "<script>alert(1)</script>", "Test");

        String html = mockMvc.perform(get("/admin/paie?mois=2026-03").session(s))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void lesFlechesDeMois_seNommentPourUnLecteurDEcran() throws Exception {
        // Sans nom accessible, le lien prend pour nom le texte de la ligature de l icone : un
        // lecteur d ecran annonce « chevron_left », ce qui ne veut rien dire.
        String html = mockMvc.perform(get("/admin/paie?mois=2026-03").session(session(Plan.PRO)))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("aria-label=\"Mois précédent : 2026-02\"");
        assertThat(html).contains("aria-label=\"Mois suivant : 2026-04\"");
    }

    @Test
    void leNomDuFichier_distingueLesEtablissements() throws Exception {
        // Deux ecoles produisaient le meme « paie_2026-03.csv » : dans le dossier de
        // telechargements d un gestionnaire qui en suit plusieurs, le second ecrase le premier.
        MockHttpSession s = session(Plan.PRO);
        String attendu = "paie_" + dernierEtablissement.getSlug().replaceAll("[^A-Za-z0-9_-]", "")
                + "_2026-03.csv";

        assertThat(mockMvc.perform(get("/admin/paie/export.csv?mois=2026-03").session(s))
                .andReturn().getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"" + attendu + "\"");
    }

    @Test
    void lEcranDePaie_nEstPasReactualiseChaqueSeconde() throws Exception {
        // Le module de navigation rafraichit les pages d administration toutes les secondes.
        // Sur cet ecran, chaque rafraichissement relance tout le calcul du mois — annuaire
        // complet et toutes les seances — une fois par seconde et par onglet ouvert.
        String html = mockMvc.perform(get("/admin/paie").session(session(Plan.PRO)))
                .andReturn().getResponse().getContentAsString();

        int garde = html.indexOf("__liveAutorise");
        assertThat(garde).as("la garde d auto-actualisation doit exister").isNotNegative();
        assertThat(html.substring(garde, Math.min(html.length(), garde + 1500)))
                .as("l ecran de paie doit figurer parmi les pages exclues")
                .contains("/admin/paie");
    }

    @Test
    void leCsv_porteLesEnTetesAttendusParUnServicePaie() throws Exception {
        String csv = new String(mockMvc.perform(get("/admin/paie/export.csv?mois=2026-03")
                        .session(session(Plan.PRO)))
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

        // Le matricule en premier : c'est la clé sur laquelle le service paie rapproche ses
        // propres fiches. Un fichier trié sur le nom lui serait inutilisable.
        assertThat(csv).startsWith("﻿\"Matricule\";");
        assertThat(csv).contains("\"Heures a payer\"");
        assertThat(csv).contains("\"Seances non emargees\"");
    }
}
