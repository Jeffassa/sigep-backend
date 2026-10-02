package ci.esatic.sigep.tenant.plan;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Découpage des fonctionnalités par plan (gating premium) + quotas + tarifs.
 * Table en dur pour l'instant ; externalisable en base/config plus tard.
 */
@Service
public class PlanService {

    /** Tarifs mensuels en unité MAJEURE de la devise (XOF : pas de décimale). */
    @Value("${app.plans.pro-price:13000}")
    private long prixPro;

    @Value("${app.plans.enterprise-price:96000}")
    private long prixEnterprise;

    /** Devise de facturation (source unique, partagée avec Stripe). */
    @Value("${app.billing.currency:xof}")
    private String devise;

    @Value("${app.billing.symbole:FCFA}")
    private String symbole;

    /** Tarif mensuel facturé pour un plan (0 pour Free). */
    public long prixMensuel(Plan plan) {
        if (plan == Plan.PRO) return prixPro;
        if (plan == Plan.ENTERPRISE) return prixEnterprise;
        return 0;
    }

    /** Code ISO de la devise de facturation (minuscules, ex. "eur"). */
    public String devise() {
        return devise == null ? "eur" : devise.toLowerCase();
    }

    /** Symbole d'affichage de la devise (ex. "€"). */
    public String symbole() {
        return symbole;
    }

    /** Plans réellement achetables en ligne (tarif > 0). */
    public List<Plan> plansPayants() {
        return Stream.of(Plan.PRO, Plan.ENTERPRISE).filter(p -> prixMensuel(p) > 0).toList();
    }

    /** Le plan est-il achetable en ligne (tarif configuré) ? */
    public boolean estAchetable(Plan plan) {
        return plan != null && plan != Plan.FREE && prixMensuel(plan) > 0;
    }

    private static final Map<Plan, Set<Feature>> FEATURES = Map.of(
            Plan.FREE, EnumSet.noneOf(Feature.class),
            Plan.PRO, EnumSet.of(Feature.ANALYSE_IA, Feature.RAPPORTS_AVANCES,
                    Feature.MULTI_CAMPUS, Feature.BRANDING, Feature.EXPORT_PAIE),
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

    /**
     * Combien de campus ce plan autorise-t-il ?
     *
     * <p>Un seul sans {@link Feature#MULTI_CAMPUS} : c'est exactement ce que la grille annonce
     * sous « 1 campus » pour l'offre gratuite. Le chiffre est ici plutôt que dans la base parce
     * qu'il découpe l'offre, au même titre que la table des fonctionnalités.
     */
    public int campusAutorises(Etablissement etablissement) {
        return estDisponible(etablissement, Feature.MULTI_CAMPUS) ? Integer.MAX_VALUE : 1;
    }

    /**
     * Vérifie qu'un campus de plus reste permis, sinon refuse en nommant la limite.
     *
     * @param nbActuel nombre de campus déjà enregistrés pour cet établissement
     */
    public void verifierQuotaCampus(Etablissement etablissement, long nbActuel) {
        int max = campusAutorises(etablissement);
        if (nbActuel >= max) {
            throw new PlanLimiteException("Votre plan autorise " + max
                    + (max > 1 ? " établissements." : " seul établissement.")
                    + " Passez à un plan supérieur pour en gérer plusieurs.");
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
