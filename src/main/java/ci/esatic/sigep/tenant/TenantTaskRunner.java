package ci.esatic.sigep.tenant;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.repository.EtablissementRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

/**
 * Exécute une tâche planifiée (cron) UNE FOIS PAR ÉTABLISSEMENT actif, dans son contexte
 * tenant. Indispensable car un job tourne hors requête HTTP : sans cela, le contexte tenant
 * n'est pas posé et le filtre d'isolation reste inactif (le job verrait tous les tenants).
 *
 * Pour chaque établissement : transaction dédiée + TenantContext posé + filtre Hibernate
 * activé → les lectures sont cloisonnées et les écritures estampillées sur le bon tenant.
 * Une erreur sur un établissement n'interrompt pas les autres.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantTaskRunner {

    private final EtablissementRepository etablissementRepository;
    private final PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    public void pourChaqueTenantActif(Consumer<Etablissement> action) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        for (Etablissement etab : etablissementRepository.findByActifTrue()) {
            try {
                tx.executeWithoutResult(status -> {
                    TenantContext.set(etab.getId());
                    em.unwrap(Session.class)
                            .enableFilter("tenantFilter")
                            .setParameter("tenantId", etab.getId());
                    action.accept(etab);
                });
            } catch (Exception e) {
                log.error("Tâche planifiée échouée pour l'établissement {} : {}", etab.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
