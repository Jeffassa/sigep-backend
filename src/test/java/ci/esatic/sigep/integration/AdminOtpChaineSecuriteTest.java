package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Quelle chaîne de sécurité sert les URL du second facteur ?
 *
 * <p>Il y en a deux : celle de l'administration web, qui tient une session, et celle de l'API,
 * qui est <b>stateless</b> et ignore donc le cookie de session. Une URL d'administration qui
 * retombe sur la seconde reçoit un 401 JSON même de la part d'un administrateur parfaitement
 * connecté — c'est ce qui est arrivé à {@code /admin-otp/renvoyer}, laissé hors du
 * {@code securityMatcher} : le bouton « Je n'ai rien reçu — renvoyer un code » était mort, alors
 * qu'il est l'un des trois filets qui empêchent un administrateur d'être enfermé dehors.
 *
 * <p><b>Ces tests n'utilisent délibérément pas {@code @WithMockUser}.</b> Cette annotation pose le
 * contexte de sécurité <i>en dehors</i> de la chaîne : la requête paraît authentifiée quelle que
 * soit la chaîne qui la sert, et le défaut cherché ici devient invisible — il l'a effectivement
 * été. On reproduit donc ce qu'un navigateur porte réellement : un cookie de session, rien d'autre.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminOtpChaineSecuriteTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;

    /** La session que le form-login laisse derrière lui, sans rien d'autre. */
    private MockHttpSession sessionNavigateur(String role) {
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin@ecole.ci", "n/a", List.of(new SimpleGrantedAuthority(role))));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);
        return session;
    }

    @Test
    void leRenvoiDUnCode_estServiParLaChaineWeb_etNonParLApiStateless() throws Exception {
        int statut = mockMvc.perform(post("/admin-otp/renvoyer")
                        .session(sessionNavigateur("ROLE_ADMIN")).with(csrf()))
                .andReturn().getResponse().getStatus();

        // 401 signifierait que la chaîne API a servi la requête en ignorant la session.
        assertThat(statut)
                .as("le renvoi de code doit etre servi par la chaine web, qui lit la session")
                .isNotEqualTo(401);
    }

    @Test
    void leRenvoiDUnCode_estOuvertAuxDeuxProfilsDAdministration() throws Exception {
        for (String role : List.of("ROLE_ADMIN", "ROLE_SUPER_ADMIN")) {
            int statut = mockMvc.perform(post("/admin-otp/renvoyer")
                            .session(sessionNavigateur(role)).with(csrf()))
                    .andReturn().getResponse().getStatus();

            assertThat(statut).as("renvoi de code pour " + role).isNotIn(401, 403);
        }
    }

    @Test
    void lesUrlsDuSecondFacteur_restentFermeesAUnEnseignant() throws Exception {
        assertThat(mockMvc.perform(get("/admin-otp").session(sessionNavigateur("ROLE_ENSEIGNANT")))
                        .andReturn().getResponse().getStatus())
                .as("un enseignant n'a rien a faire sur la page du second facteur")
                .isEqualTo(403);

        assertThat(mockMvc.perform(post("/admin-otp/renvoyer")
                        .session(sessionNavigateur("ROLE_ENSEIGNANT")).with(csrf()))
                        .andReturn().getResponse().getStatus())
                .as("ni sur le renvoi de code")
                .isEqualTo(403);
    }

    @Test
    void lesUrlsDuSecondFacteur_renvoientUnAnonymeVersLaConnexion() throws Exception {
        // Une redirection, pas un 401 JSON : c'est une page, pas une API.
        assertThat(mockMvc.perform(get("/admin-otp")).andReturn().getResponse().getStatus())
                .isEqualTo(302);
        assertThat(mockMvc.perform(post("/admin-otp/renvoyer").with(csrf()))
                        .andReturn().getResponse().getStatus())
                .as("un anonyme doit etre renvoye vers la connexion, pas recevoir un 401 d'API")
                .isEqualTo(302);
    }
}
