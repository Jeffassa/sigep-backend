package ci.esatic.sigep.security.sso;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * Un établissement sans SSO utilisable ne doit pas produire une erreur serveur.
 *
 * <p>Le résolveur de Spring lève une exception quand l'identifiant demandé ne correspond à aucune
 * inscription, et cette exception part d'un filtre — donc avant le contrôleur, hors de portée de
 * tout gestionnaire d'exception applicatif. Le visiteur reçoit alors un <b>500</b>.
 *
 * <p>C'est pourtant un cas parfaitement ordinaire, et il est même certain d'arriver : un
 * établissement qui redescend d'offre, un abonnement qui expire, une configuration désactivée le
 * temps d'une panne — à chaque fois, {@link RegistreSsoTenant} refuse l'inscription, à juste
 * titre. Un lien SSO mis en favori par un agent produirait alors « erreur du serveur », là où il
 * n'y a qu'une décision commerciale ou un interrupteur.
 *
 * <p>Ici, le refus devient une absence : le filtre laisse simplement passer la requête, et
 * {@code SsoWebController} la recueille pour renvoyer vers la connexion par mot de passe, avec
 * une phrase qui a du sens. Rien n'est affaibli — c'est le registre qui décide, et il décide
 * exactement comme avant.
 */
@Component
public class ResolveurDemandeSso implements OAuth2AuthorizationRequestResolver {

    /** Doit rester aligné sur le chemin déclaré dans la chaîne de sécurité. */
    public static final String BASE = "/oauth2/authorization";

    private final OAuth2AuthorizationRequestResolver delegue;

    public ResolveurDemandeSso(RegistreSsoTenant registre) {
        this.delegue = new DefaultOAuth2AuthorizationRequestResolver(registre, BASE);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest requete) {
        return sansLever(() -> delegue.resolve(requete));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest requete, String registrationId) {
        return sansLever(() -> delegue.resolve(requete, registrationId));
    }

    private static OAuth2AuthorizationRequest sansLever(
            java.util.function.Supplier<OAuth2AuthorizationRequest> appel) {
        try {
            return appel.get();
        } catch (IllegalArgumentException e) {
            // « Invalid Client Registration with Id » : l'identifiant ne mène à rien
            // d'utilisable. Pour nous, cela veut dire « pas de SSO ici », pas « panne ».
            return null;
        }
    }
}
