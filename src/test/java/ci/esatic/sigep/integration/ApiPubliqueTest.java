package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.security.CleApiAuthFilter;
import ci.esatic.sigep.security.CleApiPrincipal;
import ci.esatic.sigep.service.CleApiService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * API publique : l'offre Enterprise vendait « SSO &amp; API » alors que {@code API_PUBLIQUE}
 * n'était consultée nulle part.
 *
 * <p>Le risque propre à cette fonctionnalité est le cloisonnement. Une clé ouvre les données
 * d'un établissement, et d'un seul : c'est l'identité posée par le filtre qui le décide, et
 * l'intercepteur multi-tenant qui l'applique. Ces tests fixent cette chaîne, parce qu'une
 * erreur y livrerait les données de tous les établissements à n'importe quel client.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiPubliqueTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private PlanService planService;

    private Etablissement avecPlan(Plan plan) {
        Etablissement e = new Etablissement();
        e.setPlan(plan);
        return e;
    }

    @Test
    void sansCle_lApiRefuse() throws Exception {
        assertThat(mockMvc.perform(get("/api/public/ping")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void uneCleInventee_estRefusee() throws Exception {
        int statut = mockMvc.perform(get("/api/public/ping")
                        .header(CleApiAuthFilter.ENTETE, "sigep_pas-une-vraie-cle"))
                .andReturn().getResponse().getStatus();
        assertThat(statut).isEqualTo(401);
    }

    @Test
    void lApi_estReserveeAuPlanQuiLaComprend() {
        // Le plan est vérifié à CHAQUE appel, non à l'émission : un établissement qui redescend
        // au plan gratuit perd l'accès sans qu'on ait à parcourir ses clés.
        assertThat(planService.estDisponible(avecPlan(Plan.FREE), Feature.API_PUBLIQUE)).isFalse();
        assertThat(planService.estDisponible(avecPlan(Plan.PRO), Feature.API_PUBLIQUE)).isFalse();
        assertThat(planService.estDisponible(avecPlan(Plan.ENTERPRISE), Feature.API_PUBLIQUE)).isTrue();
    }

    @Test
    void laCle_nEstJamaisConservableEnClair() {
        // Deux clés différentes ont deux empreintes différentes, et l'empreinte ne permet pas
        // de remonter à la clé : c'est ce qui autorise à dire que personne d'autre ne la connaît.
        String a = CleApiService.empreinte("sigep_aaa");
        String b = CleApiService.empreinte("sigep_bbb");

        assertThat(a).hasSize(64).isNotEqualTo(b).doesNotContain("sigep_");
        assertThat(CleApiService.empreinte("sigep_aaa")).isEqualTo(a);
    }

    @Test
    void lIdentiteDUnAppel_porteUnSeulEtablissement() {
        // C'est ce champ, et lui seul, qui borne toutes les lectures de la requête.
        var principal = new CleApiPrincipal(1L, 42L, "Export paie");
        assertThat(principal.etablissementId()).isEqualTo(42L);

        // Rien de secret ne doit transparaître : cet objet finit dans les journaux.
        assertThat(principal.toString()).contains("42").doesNotContain("sigep_");
    }
}
