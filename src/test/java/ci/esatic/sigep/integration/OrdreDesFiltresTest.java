package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un plafond de débit posé derrière le filtre qu'il devait protéger ne protège rien.
 *
 * <p>Les budgets se lisent dans {@code RateLimitFilter}, et il est inséré par
 * {@code addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)}. C'est
 * exact pour la connexion par mot de passe. Mais les filtres OAuth2 ne sont pas à cet
 * emplacement-là : {@code OAuth2AuthorizationRequestRedirectFilter} s'exécute <b>bien plus
 * tôt</b> dans la chaîne, et il répond — il redirige vers le fournisseur d'identité — sans
 * jamais rendre la main.
 *
 * <p>Conséquence, si l'ordre est mauvais : le budget écrit pour {@code /oauth2/authorization/**}
 * n'est consulté sur aucune requête. Or c'est précisément le chemin qui déclenche un appel
 * réseau sortant vers l'infrastructure d'un client, et il est ouvert sans authentification.
 *
 * <p>Rien ne le signalerait : le code du budget existe, il se lit bien, et il n'est jamais
 * atteint. D'où ce test, qui regarde la chaîne telle que Spring l'a réellement assemblée.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrdreDesFiltresTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private FilterChainProxy chaines;

    @Test
    void leLimiteurDeDebit_sExecuteAvantLesFiltresOAuth2() {
        SecurityFilterChain admin = chaineQuiCapte("/oauth2/authorization/ecole");
        List<String> noms = admin.getFilters().stream()
                .map(f -> f.getClass().getSimpleName()).toList();

        int limiteur = noms.indexOf("RateLimitFilter");
        int departOAuth2 = noms.indexOf("OAuth2AuthorizationRequestRedirectFilter");
        int retourOAuth2 = noms.indexOf("OAuth2LoginAuthenticationFilter");

        assertThat(limiteur).as("RateLimitFilter absent de la chaine d'administration").isNotNegative();
        assertThat(departOAuth2).as("filtre de depart OAuth2 absent : le SSO n'est pas cable")
                .isNotNegative();
        assertThat(retourOAuth2).as("filtre de retour OAuth2 absent : le SSO n'est pas cable")
                .isNotNegative();

        assertThat(limiteur)
                .as("ordre reel de la chaine : " + noms + " — le limiteur doit precéder le depart "
                        + "OAuth2, sinon le budget de /oauth2/authorization/** n'est jamais lu")
                .isLessThan(departOAuth2);
        assertThat(limiteur)
                .as("le limiteur doit aussi preceder le retour du fournisseur")
                .isLessThan(retourOAuth2);
    }

    @Test
    void lesQuatreAdressesDuSso_sontCapteesParLaChaineDAdministration() {
        // Laissées à la chaîne d'API, elles seraient SANS ÉTAT : le « state » et le « nonce »
        // d'OpenID Connect vivent en session, et l'échange échouerait à chaque fois.
        for (String url : List.of("/sso", "/sso/ecole", "/oauth2/authorization/ecole",
                "/login/oauth2/code/ecole")) {
            List<String> noms = chaineQuiCapte(url).getFilters().stream()
                    .map(f -> f.getClass().getSimpleName()).toList();
            assertThat(noms)
                    .as(url + " doit tomber sur la chaine d'administration (avec sessions et CSRF)")
                    .contains("UsernamePasswordAuthenticationFilter", "CsrfFilter");
        }
    }

    private SecurityFilterChain chaineQuiCapte(String chemin) {
        var requete = new org.springframework.mock.web.MockHttpServletRequest("GET", chemin);
        requete.setServletPath(chemin);
        for (SecurityFilterChain c : chaines.getFilterChains()) {
            if (c.matches(requete)) return c;
        }
        throw new AssertionError("aucune chaine de securite ne capte " + chemin);
    }
}
