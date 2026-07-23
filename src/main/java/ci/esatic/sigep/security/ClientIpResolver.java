package ci.esatic.sigep.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Résolution UNIQUE de l'IP cliente (source de vérité partagée).
 * X-Forwarded-For n'est lu QUE derrière un reverse proxy de confiance
 * ({@code app.security.trust-forwarded-for=true}), car ce header est falsifiable
 * en accès direct. Centralisé pour éviter des copies divergentes (faille silencieuse).
 */
@Component
public class ClientIpResolver {

    @Value("${app.security.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    public String resolve(HttpServletRequest request) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
