package ci.esatic.sigep.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limiteur de débit par IP (fenêtre glissante d'une minute, réponse HTTP 429).
 * Deux niveaux cumulables :
 * <ol>
 *   <li><b>Budgets STRICTS</b> sur les POST sensibles (login, refresh, inscription d'établissement,
 *       login web admin) — anti-bruteforce d'identifiants et anti-spam d'inscription.</li>
 *   <li><b>Budget GLOBAL</b> par IP sur toutes les autres requêtes — filet contre les floods ciblés.
 *       Exclut le statique, la sonde de santé et l'affichage QR kiosque (rafraîchi en continu).</li>
 * </ol>
 *
 * État en mémoire (adapté à un déploiement mono-instance), avec <b>purge planifiée</b> et
 * <b>plafond de clés</b> : le limiteur ne peut pas devenir lui-même un vecteur d'épuisement
 * mémoire sous rotation d'IP.
 *
 * <p>NB : ceci ne protège PAS d'un DDoS volumétrique réseau — cela relève d'un bouclier amont
 * (CDN/WAF type Cloudflare). Ici on couvre l'abus applicatif (bruteforce, scraping, floods ciblés).
 * Aucune dépendance externe requise.
 */
@Component
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L; // 1 minute
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REFRESH_PATH = "/api/auth/refresh";
    private static final String SIGNUP_PATH = "/api/saas/etablissements";
    private static final String ADMIN_LOGIN_PATH = "/admin-login";

    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** Permet de désactiver le rate-limiting (ex. profil de test). Activé par défaut. */
    @Value("${app.security.login-rate-limit.enabled:true}")
    private boolean enabled;

    /** Budget login par IP/min (anti-bruteforce d'identifiants). */
    @Value("${app.security.login-rate-limit.max-login:5}")
    private int maxLogin;

    /** Budget refresh par IP/min. Plus large : le refresh légitime est fréquent et le token
     *  est à haute entropie. Évite de pénaliser plusieurs utilisateurs derrière un même NAT. */
    @Value("${app.security.login-rate-limit.max-refresh:30}")
    private int maxRefresh;

    /** Budget d'inscriptions d'établissement par IP/min (endpoint public anti-spam). */
    @Value("${app.security.login-rate-limit.max-signup:5}")
    private int maxSignup;

    /** Budget du login web admin (formulaire) par IP/min — anti-bruteforce du back-office. */
    @Value("${app.security.login-rate-limit.max-admin-login:10}")
    private int maxAdminLogin;

    /** Budget GLOBAL par IP/min, toutes routes confondues (0 = désactivé). Volontairement
     *  généreux : plusieurs utilisateurs partagent souvent une IP (NAT d'établissement). */
    @Value("${app.security.login-rate-limit.max-global:300}")
    private int maxGlobal;

    /** Plafond de clés en mémoire (garde anti-épuisement). Au-delà, aucune NOUVELLE clé n'est
     *  enregistrée (fail-open borné) en attendant la purge — la mémoire reste bornée. */
    @Value("${app.security.login-rate-limit.max-keys:50000}")
    private int maxKeys;

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

        if (enabled) {
            String ip = resolveClientIp(request);

            // 1) Budget strict par endpoint sensible (clé par (IP, chemin) : budgets indépendants).
            int strict = budgetStrict(request);
            if (strict > 0 && estBloque(ip + "|" + request.getRequestURI(), strict)) {
                refuser(request, response, ip);
                return;
            }

            // 2) Budget global par IP (filet anti-flood), hors chemins à fort trafic légitime.
            if (maxGlobal > 0 && !estExclu(request) && estBloque(ip + "|*", maxGlobal)) {
                refuser(request, response, ip);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /** Budget applicable au POST sensible (0 = non concerné). */
    private int budgetStrict(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return 0;
        String uri = request.getRequestURI();
        if (LOGIN_PATH.equals(uri)) return maxLogin;
        if (REFRESH_PATH.equals(uri)) return maxRefresh;
        if (SIGNUP_PATH.equals(uri)) return maxSignup;
        if (ADMIN_LOGIN_PATH.equals(uri)) return maxAdminLogin;
        return 0;
    }

    /** Chemins exclus du budget global (trafic légitime intense, à ne pas couper). */
    private boolean estExclu(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) return false;
        return uri.startsWith("/css/") || uri.startsWith("/js/") || uri.startsWith("/images/")
                || uri.startsWith("/webjars/") || "/favicon.ico".equals(uri)
                || uri.startsWith("/actuator/health")
                || uri.startsWith("/api/qr/display/"); // écran kiosque : rafraîchi en continu
    }

    /** Enregistre un hit et indique si la clé dépasse son budget sur la fenêtre glissante. */
    private boolean estBloque(String cle, int max) {
        long now = System.currentTimeMillis();
        // Garde mémoire : au plafond, ne pas créer de nouvelle clé (fail-open borné).
        if (hits.size() >= maxKeys && !hits.containsKey(cle)) return false;
        Deque<Long> dq = hits.compute(cle, (k, deque) -> {
            if (deque == null) deque = new ArrayDeque<>();
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                deque.pollFirst();
            }
            deque.addLast(now);
            return deque;
        });
        return dq.size() > max;
    }

    private void refuser(HttpServletRequest request, HttpServletResponse response, String ip) throws IOException {
        log.warn("Rate limit atteint IP={} {} {}", ip, request.getMethod(), request.getRequestURI());
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/")) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                "{\"success\":false,\"message\":\"Trop de requêtes. Réessayez dans une minute.\"}"
            );
        } else {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Trop de requêtes. Réessayez dans une minute.");
        }
    }

    /** Purge périodique des clés dont la fenêtre est vide — borne la mémoire dans le temps. */
    @Scheduled(fixedDelay = WINDOW_MS)
    void purger() {
        long now = System.currentTimeMillis();
        hits.forEach((cle, dq) -> hits.computeIfPresent(cle, (k, deque) -> {
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                deque.pollFirst();
            }
            return deque.isEmpty() ? null : deque; // null => l'entrée est retirée
        }));
    }

    /**
     * Résout l'IP cliente. N'utilise X-Forwarded-For que si l'application est explicitement
     * déclarée derrière un proxy de confiance (trustForwardedFor=true), car ce header est
     * falsifiable par le client en accès direct.
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
