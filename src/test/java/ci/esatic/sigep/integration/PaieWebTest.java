package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
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

    /** Une session d'administrateur d'un établissement au plan demandé. */
    private MockHttpSession session(Plan plan) {
        Etablissement etablissement = etablissementRepository.save(Etablissement.builder()
                .nom("Ecole " + plan).slug("ecole-paie-web-" + System.nanoTime())
                .plan(plan).build());
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
                .isEqualTo("attachment; filename=\"paie_2026-03.csv\"");
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
                .isEqualTo("attachment; filename=\"paie_2026-03.xlsx\"");
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
