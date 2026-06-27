package ci.esatic.sigep.tenant.plan;

/**
 * Limite d'abonnement atteinte (fonctionnalité non incluse, ou quota dépassé).
 * Rendue en HTTP 403 par le GlobalExceptionHandler, avec un message orientant
 * vers une montée en gamme.
 */
public class PlanLimiteException extends RuntimeException {
    public PlanLimiteException(String message) {
        super(message);
    }
}
