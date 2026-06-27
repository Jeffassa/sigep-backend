package ci.esatic.sigep.tenant.plan;

import ci.esatic.sigep.entity.Plan;

/** Fonctionnalité non incluse dans le plan courant. */
public class FeatureVerrouilleeException extends PlanLimiteException {
    public FeatureVerrouilleeException(Feature feature, Plan plan) {
        super("Fonctionnalité « " + feature + " » non incluse dans le plan " + plan
                + ". Passez à un plan supérieur pour l'activer.");
    }
}
