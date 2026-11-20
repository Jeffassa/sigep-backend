package ci.esatic.sigep.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Tire pour chaque réponse un nonce de politique de sécurité des scripts (CSP).
 *
 * <p>Seuls les scripts qui portent ce nonce s'exécutent ; ceux qu'ils chargent eux-mêmes héritent
 * de la confiance ({@code 'strict-dynamic'}). Un script injecté dans une page, par une donnée mal
 * échappée par exemple, n'a pas le nonce et reste lettre morte. Les gestionnaires en ligne
 * ({@code onclick=}…) et les liens {@code javascript:} sont refusés : les pages déclarent leurs
 * actions par attributs {@code data-*} (voir {@code /js/actions.js}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class CspNonceFilter extends OncePerRequestFilter {

    /** Attribut de requête lu par les gabarits ({@code th:nonce="${cspNonce}"}) et l'en-tête. */
    public static final String ATTRIBUT = "cspNonce";

    private static final SecureRandom ALEA = new SecureRandom();

    /**
     * {@code 'unsafe-inline'} et {@code https:} ne servent qu'aux navigateurs trop anciens pour
     * comprendre les nonces : les autres les ignorent dès qu'un nonce est présent.
     */
    public static String politique(String nonce) {
        return "script-src 'nonce-" + nonce + "' 'strict-dynamic' 'unsafe-inline' https:; "
                + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'";
    }

    /** Politique de repli, sans nonce : documentation OpenAPI (hors production) et réponses sans page. */
    public static final String POLITIQUE_SANS_NONCE = "object-src 'none'; base-uri 'self'; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        byte[] octets = new byte[18];
        ALEA.nextBytes(octets);
        request.setAttribute(ATTRIBUT, Base64.getEncoder().encodeToString(octets));
        chain.doFilter(request, response);
    }

    /** L'interface de documentation OpenAPI embarque ses scripts sans nonce. */
    public static boolean horsPolitiqueStricte(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && (uri.startsWith("/swagger-ui") || uri.startsWith("/v3/api-docs"));
    }
}
