package ci.esatic.sigep.security;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Résolution UNIQUE de l'IP cliente (source de vérité partagée).
 *
 * <p>Derrière Cloudflare puis l'hébergeur, l'en-tête X-Forwarded-For arrive sous la forme
 * {@code client, ip-cloudflare} : chaque relais ajoute à DROITE l'adresse qui l'a contacté. Seule
 * la partie droite est fiable ; la partie gauche est écrite par l'appelant, qui peut y mettre ce
 * qu'il veut. On remonte donc la liste depuis la droite en sautant les relais connus (réseaux
 * privés de l'hébergeur, plages publiées de Cloudflare) : la première adresse qui n'en est pas
 * un est le client.
 *
 * <p>Un appel qui contourne Cloudflare (adresse directe de l'hébergeur) donne
 * {@code fausse-ip, vraie-ip} : la vraie IP, à droite, n'étant pas un relais connu, c'est elle
 * qui est retenue. La fausse n'est jamais lue.
 *
 * <p>L'en-tête est lu sur la requête d'ORIGINE : le filtre {@code ForwardedHeaderFilter} de Spring
 * (stratégie {@code framework}) masque X-Forwarded-For et remplace l'adresse distante par la valeur
 * la plus à gauche, celle que l'appelant choisit. {@link IpClienteFilter} corrige ensuite
 * {@code getRemoteAddr()} pour tout le reste de l'application.
 */
@Component
public class ClientIpResolver {

    /** Plages publiées par Cloudflare (cloudflare.com/ips). */
    static final List<String> PLAGES_CLOUDFLARE = List.of(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
            "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
            "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32");

    /** Réseaux non routables sur internet : seuls des relais internes de l'hébergeur s'y trouvent. */
    static final List<String> PLAGES_PRIVEES = List.of(
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "100.64.0.0/10",
            "169.254.0.0/16", "::1/128", "fc00::/7", "fe80::/10");

    /** Une adresse IP littérale, jamais un nom d'hôte (aucune résolution DNS). */
    private static final Pattern IP_LITTERALE = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    private final boolean trustForwardedFor;
    private final List<IpAddressMatcher> relais;

    public ClientIpResolver(
            @Value("${app.security.trust-forwarded-for:false}") boolean trustForwardedFor,
            @Value("${app.security.proxys-de-confiance:}") String proxysSupplementaires) {
        this.trustForwardedFor = trustForwardedFor;
        List<String> plages = new ArrayList<>(PLAGES_CLOUDFLARE);
        plages.addAll(PLAGES_PRIVEES);
        Arrays.stream(proxysSupplementaires.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).forEach(plages::add);
        this.relais = plages.stream().map(IpAddressMatcher::new).toList();
    }

    public String resolve(HttpServletRequest request) {
        HttpServletRequest origine = origine(request);
        String pair = origine.getRemoteAddr();
        // Le pair TCP est le maillon le plus à droite de la chaîne : s'il n'est pas un relais
        // connu, c'est le client lui-même, et l'en-tête (qu'il a pu écrire) ne compte pas.
        if (!trustForwardedFor || pair == null || !estRelais(pair)) return pair;
        String xff = origine.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) return pair;

        String[] parts = xff.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String ip = parts[i].trim();
            if (!IP_LITTERALE.matcher(ip).matches()) {
                // Valeur illisible : tout ce qui est à sa gauche vient de l'appelant. On s'arrête.
                break;
            }
            if (!estRelais(ip)) return ip;
        }
        return pair;
    }

    boolean estRelais(String ip) {
        if (!IP_LITTERALE.matcher(ip).matches()) return false;
        try {
            for (IpAddressMatcher m : relais) {
                if (m.matches(ip)) return true;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        return false;
    }

    /** Remonte les enveloppes successives jusqu'à la requête reçue par le serveur. */
    private static HttpServletRequest origine(HttpServletRequest request) {
        ServletRequest r = request;
        while (r instanceof ServletRequestWrapper w && w.getRequest() instanceof HttpServletRequest) {
            r = w.getRequest();
        }
        return (HttpServletRequest) r;
    }
}
