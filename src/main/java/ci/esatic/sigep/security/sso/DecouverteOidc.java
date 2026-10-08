package ci.esatic.sigep.security.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.stereotype.Component;

import javax.net.ssl.HttpsURLConnection;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * La découverte OIDC, isolée derrière une interface — et tenue en laisse.
 *
 * <p>C'est le <b>seul appel sortant</b> de tout le SSO, et il part vers une adresse qu'un client
 * nous a dictée. L'isoler ici a deux effets : les tests peuvent décrire un fournisseur d'identité
 * sans en héberger un, et tout ce qui concerne la connexion elle-même tient en un seul endroit.
 *
 * <h2>Pourquoi ce n'est pas {@code ClientRegistrations.fromIssuerLocation}</h2>
 *
 * <p>La méthode de Spring fait exactement ce qu'il faut sur le plan de la spécification, mais
 * elle utilise un client HTTP par défaut : <b>sans délai d'attente</b> et qui <b>suit les
 * redirections</b>. Les deux comptent ici.
 *
 * <ul>
 *   <li>Sans délai, un fournisseur qui accepte la connexion puis ne répond jamais retient un fil
 *       de traitement du serveur <b>indéfiniment</b>. L'adresse qui déclenche cet appel est
 *       ouverte sans authentification : une poignée de requêtes suffirait à immobiliser
 *       l'instance — celle que partagent tous les autres établissements.</li>
 *   <li>Les redirections rouvrent la porte que {@link EmetteurAutorise} vient de fermer : une
 *       adresse publique qui renvoie vers {@code https://10.0.0.5/} ferait partir la requête
 *       depuis l'intérieur de notre infrastructure, sans qu'aucun contrôle ne s'applique au
 *       second saut.</li>
 * </ul>
 *
 * <p>La lecture est donc faite ici, avec une connexion bornée dans le temps, qui ne suit aucune
 * redirection et ne lit qu'un document de taille plafonnée. Ce que le document déclare est
 * ensuite revérifié par {@code RegistreSsoTenant} : un fournisseur peut nommer les points d'accès
 * qu'il veut, il ne nous fera pas pour autant parler à une machine interne.
 */
public interface DecouverteOidc {

    /**
     * Interroge l'émetteur et renvoie une inscription pré-remplie.
     *
     * @throws SsoIndisponibleException si l'émetteur est injoignable ou ne répond pas en OIDC
     */
    ClientRegistration.Builder decouvrir(String issuer);

    /** Implémentation réelle : lecture directe du document de découverte, sous contrainte. */
    @Component
    @Slf4j
    class ParDecouverteReseau implements DecouverteOidc {

        /** Assez pour une liaison intercontinentale, trop peu pour immobiliser un fil. */
        private static final int DELAI_CONNEXION_MS = 4_000;
        private static final int DELAI_LECTURE_MS = 6_000;

        /** Un document de découverte réel pèse quelques kilo-octets. */
        private static final int TAILLE_MAX = 256 * 1024;

        private static final String CHEMIN = "/.well-known/openid-configuration";

        private final ObjectMapper json = new ObjectMapper();

        @Override
        public ClientRegistration.Builder decouvrir(String issuer) {
            String base = issuer == null ? "" : issuer.trim();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            // Revérifié ici même si l'appelant l'a déjà fait : c'est la ligne juste avant la
            // connexion, et c'est la seule qui compte vraiment.
            EmetteurAutorise.verifier(base);

            Map<String, Object> document = lire(base + CHEMIN);

            // L'émetteur annoncé DOIT être celui qu'on a demandé (OpenID Connect Discovery, §4.3).
            // Sans cette égalité, un document hébergé n'importe où pourrait se faire passer pour
            // celui d'un autre fournisseur.
            String annonce = texte(document, "issuer");
            if (!Objects.equals(base, annonce)) {
                throw new SsoIndisponibleException(
                        "Le fournisseur se déclare sous l'adresse « " + annonce + " » alors que "
                        + "vous avez saisi « " + base + " ». Reprenez exactement l'adresse que "
                        + "votre fournisseur indique comme « issuer ».");
            }

            String autorisation = exige(document, "authorization_endpoint");
            String jeton = exige(document, "token_endpoint");
            String cles = exige(document, "jwks_uri");
            String infos = texte(document, "userinfo_endpoint");

            ClientRegistration.Builder inscription = ClientRegistration.withRegistrationId("decouverte")
                    .issuerUri(base)
                    .authorizationUri(autorisation)
                    .tokenUri(jeton)
                    .jwkSetUri(cles);
            if (infos != null && !infos.isBlank()) {
                // « sub » et non une autre revendication : c'est le seul identifiant qu'OpenID
                // Connect garantit stable et unique chez un fournisseur. L'adresse e-mail, elle,
                // se réattribue.
                inscription.userInfoUri(infos).userNameAttributeName(IdTokenClaimNames.SUB);
            }
            return inscription;
        }

        /** Lit le document, sans suivre de redirection et sans attendre indéfiniment. */
        private Map<String, Object> lire(String url) {
            HttpURLConnection connexion = null;
            try {
                connexion = (HttpURLConnection) URI.create(url).toURL().openConnection();
                if (!(connexion instanceof HttpsURLConnection)) {
                    // Ceinture et bretelles : EmetteurAutorise a déjà exigé https, mais une
                    // connexion en clair ne doit jamais pouvoir s'ouvrir d'ici.
                    throw new SsoIndisponibleException(
                            "La découverte doit se faire en HTTPS.");
                }
                connexion.setRequestMethod("GET");
                connexion.setConnectTimeout(DELAI_CONNEXION_MS);
                connexion.setReadTimeout(DELAI_LECTURE_MS);
                // LE point qui compte : une redirection rouvrirait la porte que le filtre
                // d'adresses vient de fermer, en faisant partir le second saut sans contrôle.
                connexion.setInstanceFollowRedirects(false);
                connexion.setRequestProperty("Accept", "application/json");

                int code = connexion.getResponseCode();
                if (code != 200) {
                    throw new SsoIndisponibleException(
                            "Le fournisseur a répondu « " + code + " » à l'adresse de découverte. "
                            + (code >= 300 && code < 400
                                ? "Une redirection n'est pas acceptée : saisissez l'adresse finale."
                                : "Vérifiez l'adresse de l'émetteur."));
                }

                try (InputStream flux = connexion.getInputStream()) {
                    byte[] contenu = flux.readNBytes(TAILLE_MAX);
                    if (contenu.length >= TAILLE_MAX) {
                        throw new SsoIndisponibleException(
                                "La réponse du fournisseur est anormalement volumineuse.");
                    }
                    return json.readValue(new String(contenu, StandardCharsets.UTF_8), Map.class);
                }
            } catch (SsoIndisponibleException e) {
                throw e;
            } catch (Exception e) {
                // Le détail technique reste au journal : ce qui remonte à l'écran ne doit rien
                // apprendre sur notre installation ni sur la topologie du réseau.
                log.warn("SSO : découverte impossible auprès de {} ({})",
                        url, e.getClass().getSimpleName());
                throw new SsoIndisponibleException(
                        "Aucun fournisseur OpenID Connect n'a répondu à cette adresse.");
            } finally {
                if (connexion != null) {
                    connexion.disconnect();
                }
            }
        }

        private static String texte(Map<String, Object> document, String cle) {
            Object v = document == null ? null : document.get(cle);
            return v instanceof String s ? s : null;
        }

        private static String exige(Map<String, Object> document, String cle) {
            String v = texte(document, cle);
            if (v == null || v.isBlank()) {
                throw new SsoIndisponibleException(
                        "Le document de découverte ne contient pas « " + cle + " » : ce n'est pas "
                        + "un fournisseur OpenID Connect complet.");
            }
            return v;
        }
    }
}
