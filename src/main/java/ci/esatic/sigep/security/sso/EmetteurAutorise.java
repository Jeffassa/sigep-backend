package ci.esatic.sigep.security.sso;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Filtre les URL d'émetteur OIDC que le serveur acceptera d'aller interroger.
 *
 * <h2>Pourquoi ce garde-fou existe</h2>
 *
 * <p>L'émetteur est saisi par un administrateur d'établissement, et c'est <b>notre serveur</b> qui
 * va le chercher pour découvrir les points d'accès du fournisseur. Autrement dit : un client nous
 * dicte une adresse, et nous la visitons depuis l'intérieur de notre infrastructure. Sans
 * contrôle, {@code http://169.254.169.254/} ferait lire à notre serveur les identifiants de son
 * propre hébergeur, et {@code http://localhost:5432} servirait à balayer nos services internes.
 * C'est le schéma classique de la falsification de requête côté serveur (SSRF).
 *
 * <p>Deux règles, et elles sont étroites :
 * <ul>
 *   <li><b>HTTPS obligatoire.</b> Aucune exception, pas même en développement : un échange OIDC
 *       en clair expose le jeton d'identité sur le réseau, et la spécification l'exige déjà.</li>
 *   <li><b>Aucune adresse interne.</b> Boucle locale, lien-local, réseaux privés, multicast :
 *       autant d'adresses qui ne désignent jamais un fournisseur d'identité public, et toujours
 *       quelque chose qui nous appartient.</li>
 * </ul>
 *
 * <h2>Où ce filtre s'applique</h2>
 *
 * <p>À <b>toutes</b> les adresses que le serveur visitera, et pas seulement à celle qu'on a
 * saisie. C'est une correction, pas une précision : vérifier le seul émetteur laissait la porte
 * grande ouverte, puisque le document de découverte — rédigé par le fournisseur — nomme ensuite
 * le point d'accès des jetons, celui des clés et celui des informations d'utilisateur. Un
 * document déclarant {@code "token_endpoint": "http://10.0.0.5:9200/"} aurait fait poster à notre
 * serveur, vers une machine interne et <b>en clair</b>, une requête portant le secret client.
 * {@code RegistreSsoTenant} repasse donc les quatre adresses ici après la découverte.
 *
 * <p>Les redirections sont refusées à la lecture du document ({@code DecouverteOidc}) : sans
 * cela, une adresse publique renvoyant vers {@code https://10.0.0.5/} aurait fait partir le
 * second saut sans aucun contrôle.
 *
 * <h2>Ce que ce garde-fou ne couvre toujours pas</h2>
 *
 * <p>Il vérifie le nom, pas la connexion. Un nom qui résout vers une adresse publique à la
 * vérification et vers une adresse interne à l'appel suivant — une réattribution DNS — passerait
 * entre les deux. S'en prémunir demande de choisir l'adresse IP au moment de la connexion, dans
 * la fabrique de sockets du client HTTP. La fenêtre est connue et assumée : elle suppose un
 * attaquant qui contrôle un domaine ET le timing de notre résolveur, et elle n'est ouverte qu'à
 * un administrateur d'un établissement déjà client, jamais à un visiteur.
 */
@Slf4j
public final class EmetteurAutorise {

    private EmetteurAutorise() {}

    /**
     * Vérifie l'URL, ou refuse en expliquant.
     *
     * @throws SsoIndisponibleException si l'adresse ne peut pas être visitée
     */
    public static void verifier(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            throw new SsoIndisponibleException("L'adresse du fournisseur d'identité est vide.");
        }
        URI uri;
        try {
            uri = URI.create(issuer.trim());
        } catch (IllegalArgumentException e) {
            throw new SsoIndisponibleException(
                    "L'adresse du fournisseur d'identité n'est pas une URL valide.");
        }

        String schema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(schema)) {
            throw new SsoIndisponibleException(
                    "L'adresse du fournisseur d'identité doit commencer par https:// — "
                    + "un échange OpenID Connect en clair exposerait le jeton d'identité.");
        }

        String hote = uri.getHost();
        if (hote == null || hote.isBlank()) {
            throw new SsoIndisponibleException(
                    "L'adresse du fournisseur d'identité ne comporte pas de nom de domaine.");
        }

        InetAddress[] adresses;
        try {
            adresses = InetAddress.getAllByName(hote);
        } catch (UnknownHostException e) {
            throw new SsoIndisponibleException(
                    "Le domaine « " + hote + " » est introuvable. Vérifiez l'adresse.");
        }
        // TOUTES les adresses, pas seulement la première : un nom qui résout vers une adresse
        // publique ET une adresse interne serait accepté sur la première venue.
        for (InetAddress adresse : adresses) {
            if (estInterne(adresse)) {
                log.warn("SSO : émetteur refusé — « {} » mène à une adresse interne.", hote);
                throw new SsoIndisponibleException(
                        "Le domaine « " + hote + " » désigne une machine du réseau interne. "
                        + "Un fournisseur d'identité doit être accessible publiquement.");
            }
        }
    }

    /** L'URL est-elle acceptable ? Forme sans exception, pour les chemins qui ne font que filtrer. */
    public static boolean estAcceptable(String issuer) {
        try {
            verifier(issuer);
            return true;
        } catch (SsoIndisponibleException e) {
            return false;
        }
    }

    private static boolean estInterne(InetAddress a) {
        return a.isLoopbackAddress()        // 127.0.0.0/8, ::1
                || a.isAnyLocalAddress()    // 0.0.0.0, ::
                || a.isLinkLocalAddress()   // 169.254.0.0/16 — métadonnées d'hébergeur, ::fe80
                || a.isSiteLocalAddress()   // 10/8, 172.16/12, 192.168/16
                || a.isMulticastAddress()
                || estUniqueLocalIPv6(a)    // fc00::/7
                || estPartagee(a);          // 100.64/10 — réseaux d'opérateur, conteneurs
    }

    /** fc00::/7 : l'équivalent IPv6 des plages privées, qu'aucun test de la JDK ne couvre. */
    private static boolean estUniqueLocalIPv6(InetAddress a) {
        byte[] o = a.getAddress();
        return o.length == 16 && (o[0] & 0xFE) == 0xFC;
    }

    /** 100.64.0.0/10 (RFC 6598) : plage partagée, invisible pour isSiteLocalAddress(). */
    private static boolean estPartagee(InetAddress a) {
        byte[] o = a.getAddress();
        return o.length == 4 && (o[0] & 0xFF) == 100 && (o[1] & 0xC0) == 0x40;
    }
}
