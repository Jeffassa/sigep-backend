package ci.esatic.sigep.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Résolution UNIQUE de l'IP cliente (source de vérité partagée).
 * X-Forwarded-For n'est lu QUE derrière un reverse proxy de confiance
 * ({@code app.security.trust-forwarded-for=true}), car ce header est falsifiable en accès direct.
 *
 * <p>SECURITE (E5) : un proxy de confiance AJOUTE l'IP réelle à DROITE de X-Forwarded-For
 * (ex. nginx {@code $proxy_add_x_forwarded_for}). On prend donc la N-ième IP EN PARTANT DE LA
 * DROITE (N = {@code app.security.trusted-proxy-count}, nombre de proxys de confiance en amont),
 * et non la plus à gauche qui, elle, est fournie — donc falsifiable — par le client
 * (contournement du rate-limit). Centralisé pour éviter des copies divergentes.
 */
@Component
public class ClientIpResolver {

    @Value("${app.security.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    /** Nombre de reverse proxys de confiance en amont (chacun ajoute une IP à droite). */
    @Value("${app.security.trusted-proxy-count:1}")
    private int trustedProxyCount;

    public String resolve(HttpServletRequest request) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                // IP vue par le proxy de confiance le plus externe = length - trustedProxyCount.
                int idx = Math.max(0, parts.length - Math.max(1, trustedProxyCount));
                String ip = parts[idx].trim();
                if (!ip.isEmpty()) return ip;
            }
        }
        return request.getRemoteAddr();
    }
}
