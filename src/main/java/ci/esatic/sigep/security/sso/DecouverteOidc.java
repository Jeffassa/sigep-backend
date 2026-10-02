package ci.esatic.sigep.security.sso;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.stereotype.Component;

/**
 * La découverte OIDC, isolée derrière une interface.
 *
 * <p>Elle part chercher {@code issuer + /.well-known/openid-configuration} sur le réseau : c'est
 * le seul appel sortant de tout le SSO. L'isoler ici a deux effets — les tests peuvent décrire un
 * fournisseur d'identité sans en héberger un, et le jour où il faudra maîtriser la connexion
 * elle-même (délais d'attente, choix de l'adresse IP contre une réattribution DNS), il n'y aura
 * qu'un seul endroit à reprendre.
 */
public interface DecouverteOidc {

    /**
     * Interroge l'émetteur et renvoie une inscription pré-remplie.
     *
     * @throws RuntimeException si l'émetteur est injoignable ou ne répond pas en OIDC
     */
    ClientRegistration.Builder decouvrir(String issuer);

    /** Implémentation réelle : celle de Spring Security, qui suit la spécification. */
    @Component
    class ParDecouverteReseau implements DecouverteOidc {
        @Override
        public ClientRegistration.Builder decouvrir(String issuer) {
            return ClientRegistrations.fromIssuerLocation(issuer);
        }
    }
}
