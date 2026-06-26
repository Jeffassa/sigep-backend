package ci.esatic.sigep.tenant.plan;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Prouve le découpage des fonctionnalités par plan + le quota d'enseignants. */
class PlanServiceTest {

    private final PlanService planService = new PlanService();

    private Etablissement etab(Plan plan, int maxEnseignants) {
        return Etablissement.builder().nom("X").slug("x").plan(plan).maxEnseignants(maxEnseignants).build();
    }

    @Test
    void free_n_a_aucune_feature_premium() {
        Etablissement e = etab(Plan.FREE, 10);
        assertThat(planService.estDisponible(e, Feature.ANALYSE_IA)).isFalse();
        assertThatThrownBy(() -> planService.exiger(e, Feature.ANALYSE_IA))
                .isInstanceOf(FeatureVerrouilleeException.class);
    }

    @Test
    void pro_a_ia_et_rapports_mais_pas_sso() {
        Etablissement e = etab(Plan.PRO, 0);
        assertThat(planService.estDisponible(e, Feature.ANALYSE_IA)).isTrue();
        assertThat(planService.estDisponible(e, Feature.RAPPORTS_AVANCES)).isTrue();
        assertThat(planService.estDisponible(e, Feature.SSO)).isFalse();
    }

    @Test
    void enterprise_a_toutes_les_features() {
        Etablissement e = etab(Plan.ENTERPRISE, 0);
        for (Feature f : Feature.values()) {
            assertThat(planService.estDisponible(e, f)).isTrue();
        }
    }

    @Test
    void quota_enseignants_du_plan_free() {
        Etablissement free = etab(Plan.FREE, 10);
        assertThat(planService.quotaEnseignantsAtteint(free, 9)).isFalse();
        assertThat(planService.quotaEnseignantsAtteint(free, 10)).isTrue();
        assertThatThrownBy(() -> planService.verifierQuotaEnseignant(free, 10))
                .isInstanceOf(PlanLimiteException.class);
    }

    @Test
    void quota_illimite_quand_max_zero() {
        Etablissement illimite = etab(Plan.PRO, 0);
        assertThat(planService.quotaEnseignantsAtteint(illimite, 100_000)).isFalse();
    }
}
