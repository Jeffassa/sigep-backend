package ci.esatic.sigep.tenant;

import ci.esatic.sigep.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.Session;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Active l'isolation tenant pour chaque requête authentifiée :
 *  - pose le TenantContext à partir de l'établissement de l'utilisateur connecté ;
 *  - active le filtre Hibernate « tenantFilter » sur la session courante, de sorte que
 *    toute lecture d'entité tenant-scoped ne retourne QUE les données de cet établissement.
 *
 * Si le principal n'est pas un utilisateur applicatif rattaché à un établissement
 * (ex. @WithMockUser de test, ou compte sans tenant), le filtre n'est pas activé.
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long tenant = tenantCourant();
        if (tenant != null) {
            TenantContext.set(tenant);
            entityManager.unwrap(Session.class)
                    .enableFilter("tenantFilter")
                    .setParameter("tenantId", tenant);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        TenantContext.clear();
    }

    private Long tenantCourant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user && user.getEtablissement() != null) {
            return user.getEtablissement().getId();
        }
        return null;
    }
}
