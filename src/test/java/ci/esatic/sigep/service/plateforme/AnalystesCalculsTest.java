package ci.esatic.sigep.service.plateforme;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Les calculs des analystes de la plateforme : prévision par tendance et risque de départ. */
class AnalystesCalculsTest {

    @Test
    void uneSerieQuiMonteDeDeuxParMoisSeProlongeAuMemeRythme() {
        assertThat(Prevision.prolonger(List.of(2L, 4L, 6L, 8L), 3)).containsExactly(10L, 12L, 14L);
    }

    @Test
    void unePrevisionNeDescendJamaisSousZero() {
        assertThat(Prevision.prolonger(List.of(6L, 4L, 2L, 0L), 3)).containsExactly(0L, 0L, 0L);
    }

    @Test
    void uneSeriePlateResteAPlat() {
        assertThat(Prevision.prolonger(List.of(3L, 3L, 3L), 2)).containsExactly(3L, 3L);
        assertThat(Prevision.tendance(List.of(3L, 3L, 3L)).pente()).isZero();
    }

    @Test
    void uneSerieVideOuReduiteAUnPointNeFaitPasEchouerLeCalcul() {
        assertThat(Prevision.prolonger(List.<Long>of(), 2)).containsExactly(0L, 0L);
        assertThat(Prevision.prolonger(List.of(5L), 2)).containsExactly(5L, 5L);
    }

    @Test
    void laFiabiliteDependDeLHistoriqueDisponible() {
        assertThat(Prevision.fiabilite(List.of(0L, 0L, 0L, 0L, 0L, 0L, 1L, 2L))).isEqualTo("faible");
        assertThat(Prevision.fiabilite(List.of(0L, 0L, 0L, 2L, 3L, 2L, 3L, 2L))).isEqualTo("moyenne");
        assertThat(Prevision.fiabilite(List.of(1L, 2L, 2L, 3L, 2L, 4L, 3L, 5L))).isEqualTo("bonne");
    }

    @Test
    void leMoisEnCoursEstCompleteAuRythmeDesMoisPrecedents() {
        // 4 inscriptions au 10 d'un mois de 30 jours, 9 par mois d'habitude : 6 de plus sur les 20 jours restants.
        assertThat(AnalystesService.projectionFinDeMois(4, 9, LocalDate.of(2026, 11, 10))).isEqualTo(10);
        // Sans historique, une inscription isolée ne devient pas une tendance.
        assertThat(AnalystesService.projectionFinDeMois(1, 0, LocalDate.of(2026, 11, 10))).isEqualTo(1);
        // Le dernier jour du mois, il n'y a plus rien à ajouter.
        assertThat(AnalystesService.projectionFinDeMois(4, 9, LocalDate.of(2026, 11, 30))).isEqualTo(4);
    }

    @Test
    void uneEcoleQuiEmargeChaqueJourNePresenteAucunRisque() {
        int score = AnalystesService.scoreRisque(1L, 90, 40, 38, 36, null);
        assertThat(score).isZero();
        assertThat(AnalystesService.niveauRisque(score)).isEqualTo("faible");
    }

    @Test
    void uneEcoleInscriteDepuisLongtempsEtJamaisUtiliseeEstARisqueEleve() {
        int score = AnalystesService.scoreRisque(null, 40, 0, 0, 0, null);
        assertThat(AnalystesService.niveauRisque(score)).isEqualTo("élevé");
        assertThat(AnalystesService.raisonRisque(null, 40, 0, 0, 0, null)).contains("jamais utilisé");
    }

    @Test
    void uneEcoleToutJusteInscriteNEstPasEncoreARisqueEleve() {
        assertThat(AnalystesService.niveauRisque(AnalystesService.scoreRisque(null, 3, 0, 0, 0, null)))
                .isNotEqualTo("élevé");
    }

    @Test
    void leSilenceLaBaisseEtLEcheanceSAdditionnent() {
        int silence = AnalystesService.scoreRisque(20L, 120, 0, 0, 0, null);
        int silenceEtBaisse = AnalystesService.scoreRisque(20L, 120, 10, 2, 20, null);
        int tout = AnalystesService.scoreRisque(20L, 120, 10, 2, 20, 5L);
        assertThat(silence).isLessThan(silenceEtBaisse);
        assertThat(silenceEtBaisse).isLessThan(tout);
        assertThat(tout).isLessThanOrEqualTo(100);
    }

    @Test
    void unAbonnementExpireEstLaPremiereRaisonDonnee() {
        assertThat(AnalystesService.raisonRisque(2L, 200, 30, 28, 30, -3L)).isEqualTo("abonnement expiré");
    }
}
