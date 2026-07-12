package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.service.AbonnementService;
import ci.esatic.sigep.service.EtablissementCourantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Blocage à l'expiration : si l'abonnement de l'établissement est expiré, toutes les pages
 * admin sont redirigées vers /admin/abonnement (seules la page d'abonnement et la
 * déconnexion restent accessibles), jusqu'au renouvellement.
 *
 * L'établissement est RELU en base (via EtablissementCourantService) : dès qu'un paiement
 * prolonge l'abonnement, l'accès est rétabli à la requête suivante, sans reconnexion.
 */
@Component
@RequiredArgsConstructor
public class AbonnementInterceptor implements HandlerInterceptor {

    private final AbonnementService abonnementService;
    private final EtablissementCourantService etablissementCourantService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String uri = request.getRequestURI();
        if (uri.startsWith("/admin/abonnement") || uri.equals("/admin/logout")) {
            return true; // toujours accessibles, même expiré
        }
        if (abonnementService.estExpire(etablissementCourantService.courant())) {
            response.sendRedirect(request.getContextPath() + "/admin/abonnement");
            return false;
        }
        return true;
    }
}
