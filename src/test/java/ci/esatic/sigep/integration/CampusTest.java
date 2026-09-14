package ci.esatic.sigep.integration;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanLimiteException;
import ci.esatic.sigep.tenant.plan.PlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le campus : « 1 campus » au plan gratuit, « multi-campus » au plan Pro.
 *
 * <p>Les deux lignes figuraient sur la grille tarifaire alors que la notion n'existait nulle
 * part : le mot n'apparaissait que dans le nom d'une constante. Ces tests fixent la limite que
 * la grille annonce, pour qu'elle cesse d'être une promesse et devienne une règle.
 */
@SpringBootTest
@ActiveProfiles("test")
class CampusTest {

    @Autowired private PlanService planService;

    private Etablissement avecPlan(Plan plan) {
        Etablissement e = new Etablissement();
        e.setPlan(plan);
        return e;
    }

    @Test
    void lePlanGratuit_estLimiteAUnSeulCampus() {
        Etablissement free = avecPlan(Plan.FREE);
        assertThat(planService.campusAutorises(free)).isEqualTo(1);

        // Le premier passe : un établissement gratuit a bien droit à son site.
        assertThatCode(() -> planService.verifierQuotaCampus(free, 0)).doesNotThrowAnyException();

        // Le second est refusé, et le refus doit nommer la limite plutôt que d'échouer sèchement.
        assertThatThrownBy(() -> planService.verifierQuotaCampus(free, 1))
                .isInstanceOf(PlanLimiteException.class)
                .hasMessageContaining("1");
    }

    @Test
    void lesPlansPayants_enGerentPlusieurs() {
        for (Plan plan : new Plan[] {Plan.PRO, Plan.ENTERPRISE}) {
            Etablissement e = avecPlan(plan);
            assertThat(planService.estDisponible(e, Feature.MULTI_CAMPUS)).isTrue();
            assertThat(planService.campusAutorises(e)).isGreaterThan(1);
            assertThatCode(() -> planService.verifierQuotaCampus(e, 12)).doesNotThrowAnyException();
        }
    }

    @Test
    void unCampus_appartientAUnEtablissement() {
        // Le cloisonnement passe par ce champ : sans lui, le filtre multi-tenant ne s'applique pas.
        var campus = ci.esatic.sigep.entity.Campus.builder().nom("Campus Nord").build();
        campus.setEtablissementId(7L);
        assertThat(campus.getEtablissementId()).isEqualTo(7L);
        assertThat(campus).isInstanceOf(ci.esatic.sigep.tenant.TenantScoped.class);
    }
}
