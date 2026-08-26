package ci.esatic.sigep.tenant;

import ci.esatic.sigep.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Active l'isolation tenant pour chaque requête authentifiée :
 *  - pose le TenantContext à partir de l'établissement de l'utilisateur connecté ;
 *  - active le filtre Hibernate « tenantFilter » sur la session liée à la requête, de sorte que
 *    toute lecture d'entité tenant-scoped ne retourne QUE les données de cet établissement.
 *
 * IMPORTANT : le filtre doit être activé sur la session RÉELLEMENT utilisée par les repositories,
 * c'est-à-dire celle liée par OpenEntityManagerInView (OSIV). D'où :
 *  - l'ordre d'enregistrement (APRÈS l'intercepteur OSIV — voir TenantWebConfig) ;
 *  - la résolution explicite via EntityManagerFactoryUtils : si aucune session n'est liée,
 *    on NE crée PAS de session jetable (le filtre y serait perdu) — on journalise en erreur,
 *    et le garde-fou @PostLoad (TenantListener) reste la ligne de défense.
 *
 * Si le principal n'est pas un utilisateur applicatif rattaché à un établissement
 * (ex. @WithMockUser de test, ou compte sans tenant), le filtre n'est pas activé.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantInterceptor implements HandlerInterceptor {

    private final EntityManagerFactory entityManagerFactory;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // Non authentifié, ou principal non applicatif (endpoints publics, @WithMockUser de test) :
        // pas de filtre tenant (ces requêtes n'accèdent pas aux données d'un établissement précis).
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return true;
        }

        // Super admin (plateforme) : vision globale, AUCUN filtre tenant — il gère tous les
        // établissements depuis /plateforme et n'a pas accès à /admin/** (rôle distinct).
        boolean superAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
        if (superAdmin) {
            return true;
        }

        // E2 (fail-closed) : un non-super-admin authentifié DOIT être rattaché à un établissement.
        // Sinon on REFUSE, plutôt que d'exécuter SANS filtre (ce qui ouvrirait une lecture
        // inter-tenant — cf. E1). L'isolation échoue ainsi en mode FERMÉ.
        if (user.getEtablissement() == null) {
            log.warn("Acces refuse : utilisateur {} authentifie sans etablissement (non super-admin).",
                    user.getId());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Compte non rattache a un etablissement.");
            return false;
        }

        Long tenant = user.getEtablissement().getId();
        TenantContext.set(tenant);
        EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
        if (em != null) {
            em.unwrap(Session.class)
                    .enableFilter("tenantFilter")
                    .setParameter("tenantId", tenant);
        } else {
            // Ne devrait jamais arriver avec OSIV actif + ordre des intercepteurs correct.
            log.error("Aucune session liée à la requête {} : filtre tenant NON activé "
                    + "(le garde-fou @PostLoad reste actif). Vérifier l'ordre des intercepteurs / OSIV.",
                    request.getRequestURI());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        TenantContext.clear();
    }
}
