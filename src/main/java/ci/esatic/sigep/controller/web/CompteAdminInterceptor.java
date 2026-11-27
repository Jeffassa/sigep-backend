package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Le principal en session est figé à la connexion : le compte est relu pour qu'un accès retiré
 * ferme la session ouverte, et qu'un mot de passe provisoire soit remplacé avant tout le reste.
 */
@Component
@RequiredArgsConstructor
public class CompteAdminInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof User u) || u.getId() == null) return true;
        User compte = userRepository.findById(u.getId()).orElse(null);
        if (compte == null || !compte.isEnabled()) {
            if (request.getSession(false) != null) request.getSession(false).invalidate();
            SecurityContextHolder.clearContext();
            response.sendRedirect(request.getContextPath() + "/admin-login?logout=true");
            return false;
        }
        String uri = request.getRequestURI();
        if (compte.isMustChangePassword() && !uri.equals("/admin/mon-compte") && !uri.equals("/admin/logout")) {
            response.sendRedirect(request.getContextPath() + "/admin/mon-compte");
            return false;
        }
        return true;
    }
}
