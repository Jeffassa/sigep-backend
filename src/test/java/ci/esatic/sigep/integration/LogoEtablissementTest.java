package ci.esatic.sigep.integration;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La marque de l'établissement est une fonctionnalité payante : elle doit l'être en fait.
 *
 * <p>{@link Feature#BRANDING} figurait dans la table des plans sans qu'aucun code ne l'exige :
 * elle séparait les offres sur la page des tarifs, jamais dans le produit. Symétriquement, le
 * plan Pro vendait « le logo de l'établissement » alors que rien ne permettait d'en déposer un.
 */
@SpringBootTest
@ActiveProfiles("test")
class LogoEtablissementTest {

    @Autowired private PlanService planService;

    private Etablissement avecPlan(Plan plan) {
        Etablissement e = new Etablissement();
        e.setPlan(plan);
        return e;
    }

    @Test
    void laMarque_estRefuseeAuPlanGratuit() {
        assertThat(planService.estDisponible(avecPlan(Plan.FREE), Feature.BRANDING)).isFalse();

        // Le refus doit être une exception, et non un simple « false » que l'appelant peut ignorer.
        assertThatThrownBy(() -> planService.exiger(avecPlan(Plan.FREE), Feature.BRANDING))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void laMarque_estAccordeeAuxPlansPayants() {
        assertThat(planService.estDisponible(avecPlan(Plan.PRO), Feature.BRANDING)).isTrue();
        assertThat(planService.estDisponible(avecPlan(Plan.ENTERPRISE), Feature.BRANDING)).isTrue();
    }

    @Test
    void lEtablissement_peutPorterUneImageEtSonType() {
        // Le type sert d'indicateur de présence : c'est lui qu'on lit à chaque page, jamais
        // l'image, qui est chargée paresseusement.
        Etablissement e = avecPlan(Plan.PRO);
        assertThat(e.getLogoType()).isNull();

        e.setLogoDonnees(new byte[] {1, 2, 3});
        e.setLogoType("image/png");

        assertThat(e.getLogoType()).isEqualTo("image/png");
        assertThat(e.getLogoDonnees()).hasSize(3);
    }
}
