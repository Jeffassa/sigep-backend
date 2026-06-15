package ci.esatic.sigep.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite les tentatives de connexion à MAX_ATTEMPTS par IP sur une fenêtre glissante de WINDOW_MS.
 * Répond HTTP 429 si la limite est dépassée.
 * Aucune dépendance externe requise — état en mémoire (suffisant pour un déploiement mono-instance).
 */
@Component
@Slf4j
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MS = 60_000L; // 1 minute
    private static final String LOGIN_PATH = "/api/auth/login";

    private final ConcurrentHashMap<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {

        if (LOGIN_PATH.equals(request.getRequestURI())
                && "POST".equalsIgnoreCase(request.getMethod())) {

            String ip = resolveClientIp(request);

            if (isRateLimited(ip)) {
                log.warn("Rate limit atteint pour IP={}", ip);
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

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        attempts.compute(ip, (k, deque) -> {
            if (deque == null) deque = new ArrayDeque<>();
            // Purge les entrées hors fenêtre glissante
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                deque.pollFirst();
            }
            deque.addLast(now);
            return deque;
        });
        return attempts.get(ip).size() > MAX_ATTEMPTS;
    }

    /** Respecte le header X-Forwarded-For positionné par un reverse proxy. */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
