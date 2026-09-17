package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Seance;

import java.time.Duration;
import java.time.LocalTime;

/**
 * La règle unique qui dit combien de temps dure une séance.
 *
 * <p>Elle a existé en quatre exemplaires. L'export paie, le rapport PDF signé, la synthèse
 * Excel et les statistiques d'un enseignant écrivaient chacun
 * {@code Duration.between(heureDebut, heureFin)} — et les trois derniers rendaient un nombre
 * <b>négatif</b> pour un cours du soir qui franchit minuit. Sur le rapport PDF, c'est-à-dire sur
 * le document qui atteste des heures faites, un cours de 21 h à minuit s'imprimait « -21,0 h ».
 *
 * <p>Quatre copies d'une règle, c'est quatre occasions de la corriger à trois endroits. Elle est
 * donc ici, une fois, et les quatre appelants viennent la chercher.
 *
 * <h2>Ce que la règle tranche</h2>
 *
 * <p>Une heure de fin postérieure au début ne pose aucune question : c'est la durée. Au-delà de
 * {@link #PLAFOND_JOURNEE_MINUTES}, la saisie est signalée plutôt que comptée — une séance ne
 * dure pas quinze heures, et payer quinze heures sur une faute de frappe coûte autant que n'en
 * payer aucune.
 *
 * <p>Une heure de fin antérieure au début a deux lectures. Soit la séance franchit minuit
 * (21 h → 00 h fait trois heures), soit les deux champs ont été intervertis (20 h → 10 h ferait
 * quatorze heures, ce qui n'est pas un cours du soir mais une journée saisie à l'envers). La
 * durée obtenue tranche : au-delà de {@link #PLAFOND_NUIT_MINUTES}, on refuse de deviner.
 *
 * <p>Le plafond de nuit est volontairement bas. Un cours qui finit après minuit commence le
 * soir et dure rarement plus de quatre heures ; à l'inverse, intervertir les bornes d'une séance
 * de durée D produit toujours 24 h − D, soit au moins dix-huit heures pour toute séance de six
 * heures ou moins. Six heures séparent donc proprement les deux cas : aucune interversion
 * plausible ne passe, aucun cours du soir réel n'est refusé.
 */
public final class DureeSeance {

    /** Au-delà, une durée « en avant » n'est plus une séance mais une saisie à vérifier. */
    public static final long PLAFOND_JOURNEE_MINUTES = 12 * 60;

    /** Au-delà, une fin antérieure au début est une interversion, non un cours du soir. */
    public static final long PLAFOND_NUIT_MINUTES = 6 * 60;

    private DureeSeance() {}

    /**
     * Durée d'une séance, et si l'on peut en faire quelque chose.
     *
     * @param minutes durée en minutes entières, nulle quand la séance n'est pas exploitable
     * @param exploitable {@code false} quand aucune lecture des horaires ne tient debout
     */
    public record Duree(long minutes, boolean exploitable) {

        public double heures() {
            return Math.round(minutes * 100.0 / 60.0) / 100.0;
        }
    }

    private static final Duree INEXPLOITABLE = new Duree(0, false);

    public static Duree de(Seance seance) {
        return seance == null ? INEXPLOITABLE : de(seance.getHeureDebut(), seance.getHeureFin());
    }

    public static Duree de(LocalTime debut, LocalTime fin) {
        if (debut == null || fin == null) return INEXPLOITABLE;
        long m = Duration.between(debut, fin).toMinutes();
        if (m > 0) return m <= PLAFOND_JOURNEE_MINUTES ? new Duree(m, true) : INEXPLOITABLE;
        if (m == 0) return INEXPLOITABLE;                  // durée nulle : saisie fautive
        long parMinuit = m + 24 * 60;
        return parMinuit <= PLAFOND_NUIT_MINUTES ? new Duree(parMinuit, true) : INEXPLOITABLE;
    }

    /**
     * Durée en heures décimales, zéro quand les horaires ne veulent rien dire.
     *
     * <p>Pour les appelants qui n'ont qu'un nombre à afficher et aucune colonne où signaler une
     * anomalie. Zéro est ici le moindre mal : c'est faux, mais visiblement faux, là où un nombre
     * négatif passait pour un total et faussait la somme qui le contenait.
     */
    public static double heures(Seance seance) {
        return de(seance).heures();
    }
}
