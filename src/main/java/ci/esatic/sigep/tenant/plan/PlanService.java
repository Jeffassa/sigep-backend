package ci.esatic.sigep.tenant.plan;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Découpage des fonctionnalités par plan (gating premium) + quotas.
 * Table en dur pour l'instant ; externalisable en base/config plus tard.
 */
@Service
public class PlanService {

    private static final Map<Plan, Set<Feature>> FEATURES = Map.of(
            Plan.FREE, EnumSet.noneOf(Feature.class),
            Plan.PRO, EnumSet.of(Feature.ANALYSE_IA, Feature.RAPPORTS_AVANCES,
                    Feature.MULTI_CAMPUS, Feature.BRANDING),
            Plan.ENTERPRISE, EnumSet.allOf(Feature.class)
    );

    /** La fonctionnalité est-elle incluse dans le plan de l'établissement ? */
    public boolean estDisponible(Etablissement etablissement, Feature feature) {
        if (etablissement == null || etablissement.getPlan() == null) return false;
        return FEATURES.getOrDefault(etablissement.getPlan(), EnumSet.noneOf(Feature.class)).contains(feature);
    }

    /** Exige la fonctionnalité, sinon lève une exception (rendue en 403). */
    public void exiger(Etablissement etablissement, Feature feature) {
        if (!estDisponible(etablissement, feature)) {
            throw new FeatureVerrouilleeException(feature,
                    etablissement == null ? null : etablissement.getPlan());
        }
    }

    /** Quota d'enseignants atteint ? (maxEnseignants = 0 → illimité) */
    public boolean quotaEnseignantsAtteint(Etablissement etablissement, long nbActuel) {
        if (etablissement == null) return false;
        int max = etablissement.getMaxEnseignants();
        return max > 0 && nbActuel >= max;
    }

    /** Vérifie le quota d'enseignants, sinon lève une exception (rendue en 403). */
    public void verifierQuotaEnseignant(Etablissement etablissement, long nbActuel) {
        if (quotaEnseignantsAtteint(etablissement, nbActuel)) {
            throw new PlanLimiteException("Quota d'enseignants atteint ("
                    + etablissement.getMaxEnseignants() + ") pour le plan " + etablissement.getPlan()
                    + ". Passez à un plan supérieur pour en ajouter davantage.");
        }
    }
}
