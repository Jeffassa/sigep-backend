package ci.esatic.sigep.security.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.stereotype.Component;

/**
 * Le jeton d'accès du fournisseur d'identité n'est pas conservé.
 *
 * <p>Par défaut, Spring range en session le jeton d'accès reçu à la fin de l'échange OIDC, pour
 * que l'application puisse ensuite appeler les API du fournisseur en votre nom. Nous ne le
 * faisons jamais : tout ce dont nous avions besoin — qui vous êtes — a été lu dans le jeton
 * d'identité et converti en session SIGEP.
 *
 * <p>Un secret qu'on n'utilise pas et qu'on garde quand même n'est rien d'autre qu'une surface
 * d'attaque supplémentaire : il donnerait, à qui mettrait la main sur une session, un accès au
 * compte Google ou Microsoft de la personne, bien au-delà de SIGEP. On ne le garde donc pas.
 *
 * <p>Conséquence à connaître : le jour où une fonctionnalité voudrait lire l'annuaire du
 * fournisseur — synchroniser les enseignants, par exemple — il faudra remplacer cette classe
 * plutôt que chercher pourquoi le jeton est absent.
 */
@Component
public class SansMemoireDesJetons implements OAuth2AuthorizedClientRepository {

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
            String registrationId, Authentication principal, HttpServletRequest request) {
        return null;
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient,
                                     Authentication principal, HttpServletRequest request,
                                     HttpServletResponse response) {
        // Volontairement vide : voir la documentation de la classe.
    }

    @Override
    public void removeAuthorizedClient(String registrationId, Authentication principal,
                                       HttpServletRequest request, HttpServletResponse response) {
        // Rien à retirer, rien n'a été rangé.
    }
}
