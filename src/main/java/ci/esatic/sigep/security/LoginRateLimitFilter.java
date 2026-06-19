package ci.esatic.sigep.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite par IP les POST sensibles d'authentification (login et refresh) sur une fenêtre
 * glissante de WINDOW_MS, avec des budgets séparés (un flood de login n'épuise pas le refresh).
 * Répond HTTP 429 si la limite est dépassée.
 * Aucune dépendance externe requise — état en mémoire (suffisant pour un déploiement mono-instance).
 */
@Component
@Slf4j
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L; // 1 minute
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REFRESH_PATH = "/api/auth/refresh";

    private final ConcurrentHashMap<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    /** Permet de désactiver le rate-limiting (ex. profil de test). Activé par défaut. */
    @Value("${app.security.login-rate-limit.enabled:true}")
    private boolean enabled;

    /** Budget login par IP/min (anti-bruteforce d'identifiants). */
    @Value("${app.security.login-rate-limit.max-login:5}")
    private int maxLogin;

    /** Budget refresh par IP/min. Plus large : le refresh légitime est fréquent et le
     *  token est à haute entropie. Évite de pénaliser plusieurs utilisateurs derrière un même NAT. */
    @Value("${app.security.login-rate-limit.max-refresh:30}")
    private int maxRefresh;

    /**
     * Ne faire confiance à X-Forwarded-For QUE derrière un reverse proxy de confiance.
     * Sinon un client peut falsifier ce header et contourner le rate-limiting.
     */
    @Value("${app.security.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {

        int limite = limitePour(request);
        if (enabled && limite > 0) {
            String ip = resolveClientIp(request);
            // Clé par (IP, chemin) : budgets login et refresh indépendants.
            String cle = ip + "|" + request.getRequestURI();
            if (isRateLimited(cle, limite)) {
                log.warn("Rate limit atteint pour IP={} chemin={}", ip, request.getRequestURI());
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                    "{\"success\":false,\"message\":\"Trop de tentatives. Réessayez dans 1 minute.\"}"
                );
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /** Limite applicable à la requête (0 = non concernée). */
    private int limitePour(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return 0;
        String uri = request.getRequestURI();
        if (LOGIN_PATH.equals(uri)) return maxLogin;
        if (REFRESH_PATH.equals(uri)) return maxRefresh;
        return 0;
    }

    private boolean isRateLimited(String cle, int max) {
        long now = System.currentTimeMillis();
        attempts.compute(cle, (k, deque) -> {
            if (deque == null) deque = new ArrayDeque<>();
            // Purge les entrées hors fenêtre glissante
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                deque.pollFirst();
            }
            deque.addLast(now);
            return deque;
        });
        return attempts.get(cle).size() > max;
    }

    /**
     * Résout l'IP cliente. N'utilise X-Forwarded-For que si l'application est
     * explicitement déclarée derrière un proxy de confiance (trustForwardedFor=true),
     * car ce header est falsifiable par le client en accès direct.
     */
    private String resolveClientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
