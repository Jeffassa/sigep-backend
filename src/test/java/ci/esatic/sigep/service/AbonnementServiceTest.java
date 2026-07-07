package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Cycle de vie de l'abonnement : expiration, rappel, prolongation. */
class AbonnementServiceTest {

    private final AbonnementService service = new AbonnementService();

    @Test
    void sans_date_d_expiration_jamais_expire() {
        Etablissement e = Etablissement.builder().nom("X").slug("x").plan(Plan.ENTERPRISE).build();
        assertThat(service.estExpire(e)).isFalse();
        assertThat(service.joursAvantExpiration(e)).isNull();
        assertThat(service.doitRappeler(e)).isFalse();
    }

    @Test
    void date_passee_est_expiree_et_rappelee() {
        Etablissement e = etab(LocalDate.now().minusDays(1));
        assertThat(service.estExpire(e)).isTrue();
        assertThat(service.doitRappeler(e)).isTrue();
    }

    @Test
    void rappel_uniquement_quand_proche() {
        assertThat(service.doitRappeler(etab(LocalDate.now().plusDays(3)))).isTrue();   // <= 7 j
        assertThat(service.doitRappeler(etab(LocalDate.now().plusDays(20)))).isFalse(); // > 7 j
        assertThat(service.estExpire(etab(LocalDate.now().plusDays(3)))).isFalse();
    }

    @Test
    void prolonger_repart_d_aujourdhui_si_expire() {
        Etablissement e = etab(LocalDate.now().minusDays(10));
        service.prolonger(e, 1);
        assertThat(e.getDateExpiration()).isEqualTo(LocalDate.now().plusMonths(1));
    }

    @Test
    void prolonger_cumule_si_encore_actif() {
        LocalDate fin = LocalDate.now().plusDays(5);
        Etablissement e = etab(fin);
        service.prolonger(e, 2);
        assertThat(e.getDateExpiration()).isEqualTo(fin.plusMonths(2));
    }

    private Etablissement etab(LocalDate expiration) {
        Etablissement e = Etablissement.builder().nom("X").slug("x").plan(Plan.PRO).build();
        e.setDateExpiration(expiration);
        return e;
    }
}
