package ci.esatic.sigep.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;

    /** Code renvoyé à l'application, qui ouvre alors l'écran de changement de mot de passe. */
    public static final String CODE_MOT_DE_PASSE = "MOT_DE_PASSE_A_CHANGER";
    static final String ROUTE_MOT_DE_PASSE = "/api/profil/mot-de-passe";

    @org.springframework.beans.factory.annotation.Value("${app.security.mot-de-passe-impose-api:true}")
    private boolean motDePasseImpose;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);

        // Parse/vérifie le token UNE seule fois. null = signature/format invalide ou expiré.
        final Claims claims = jwtService.parseAccessClaims(jwt);
        final String userEmail = (claims != null) ? claims.getSubject() : null;

        if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);
                // Un compte désactivé perd l'accès tout de suite, sans attendre l'expiration du jeton.
                if (jwtService.isValid(claims, userDetails)   // pas de re-parsing
                        && userDetails.isEnabled() && userDetails.isAccountNonLocked()) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    if (motDePasseAChanger(userDetails, request)) {
                        refuserTantQueMotDePasseProvisoire(response);
                        return;
                    }
                }
            } catch (UsernameNotFoundException e) {
                // Token de signature valide mais utilisateur supprimé/renommé : on poursuit
                // NON authentifié (→ 401 en aval) plutôt que de propager une 500.
                logger.debug("JWT valide mais utilisateur introuvable : " + userEmail);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Un mot de passe provisoire (créé par l'établissement, ou réinitialisé) ouvre la seule route
     * qui permet de le remplacer. Tant qu'il n'est pas changé, celui qui l'a lu dans un courriel ou
     * par-dessus une épaule ne peut rien faire d'autre avec.
     */
    private boolean motDePasseAChanger(UserDetails userDetails, HttpServletRequest request) {
        if (!motDePasseImpose || !(userDetails instanceof ci.esatic.sigep.entity.User u)
                || !u.isMustChangePassword()) return false;
        String uri = request.getRequestURI();
        return !(uri.equals(ROUTE_MOT_DE_PASSE) || uri.startsWith("/api/auth/"));
    }

    private static void refuserTantQueMotDePasseProvisoire(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"Choisissez votre mot de passe "
                + "personnel (onglet Profil) avant de continuer.\",\"data\":{\"code\":\"" + CODE_MOT_DE_PASSE + "\"}}");
    }
}
