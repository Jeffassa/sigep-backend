package ci.esatic.sigep.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Exécute une action pour de vrai, puis l'annule : c'est la simulation la plus fidèle qui soit,
 * puisque ce sont les mêmes règles, les mêmes contraintes de base et le même code qui tournent.
 *
 * <p>Réservé aux actions SANS effet hors de la base : un courriel parti ne s'annule pas. Une
 * action qui écrit à quelqu'un doit avoir sa propre vérification sans effet.
 *
 * <p>Les écritures sont poussées en base avant l'annulation, pour qu'une contrainte violée
 * (une salle encore utilisée par des séances, par exemple) se manifeste comme elle le ferait.
 */
@Service
public class SimulationService {

    private final TransactionTemplate transaction;

    @PersistenceContext
    private EntityManager entityManager;

    public SimulationService(PlatformTransactionManager transactionManager) {
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public <T> T simuler(Supplier<T> action) {
        return transaction.execute(etat -> {
            etat.setRollbackOnly();
            T resultat = action.get();
            try {
                entityManager.flush();
            } catch (org.hibernate.exception.ConstraintViolationException e) {
                // Le gestionnaire d'entités brut ne traduit pas les erreurs comme le font les
                // dépôts : on rend la même exception qu'aurait levée l'action réelle.
                throw new org.springframework.dao.DataIntegrityViolationException(e.getMessage(), e);
            }
            return resultat;
        });
    }
}
