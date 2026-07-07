package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.service.AbonnementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Blocage à l'expiration : si l'abonnement de l'établissement est expiré, toutes les pages
 * admin sont redirigées vers /admin/abonnement (seules la page d'abonnement et la
 * déconnexion restent accessibles), jusqu'au renouvellement.
 */
@Component
@RequiredArgsConstructor
public class AbonnementInterceptor implements HandlerInterceptor {

    private final AbonnementService abonnementService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String uri = request.getRequestURI();
        if (uri.startsWith("/admin/abonnement") || uri.equals("/admin/logout")) {
            return true; // toujours accessibles, même expiré
        }
        if (abonnementService.estExpire(etablissementCourant())) {
            response.sendRedirect(request.getContextPath() + "/admin/abonnement");
            return false;
        }
        return true;
    }

    private Etablissement etablissementCourant() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return (a != null && a.getPrincipal() instanceof User u) ? u.getEtablissement() : null;
    }
}
