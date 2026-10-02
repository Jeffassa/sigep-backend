package ci.esatic.sigep.integration;

import ci.esatic.sigep.service.DureeSeance;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La règle unique qui dit combien de temps dure une séance.
 *
 * <p>Elle a existé en quatre exemplaires : l'export paie, le rapport PDF signé, la synthèse
 * Excel et les statistiques d'un enseignant. Les trois derniers écrivaient
 * {@code Duration.between(heureDebut, heureFin)} sans se demander ce qui arrive après minuit,
 * et rendaient donc un nombre <b>négatif</b> pour un cours du soir. Sur le rapport PDF —
 * le document qui atteste des heures faites — un cours de 21 h à minuit s'imprimait
 * « -21,0 h », et ce total faussait la somme de toute la période.
 *
 * <p>Ces tests tiennent la règle à sa source, une fois pour les quatre appelants.
 */
class DureeSeanceTest {

    private static DureeSeance.Duree duree(String debut, String fin) {
        return DureeSeance.de(LocalTime.parse(debut), LocalTime.parse(fin));
    }

    @Test
    void uneSeanceOrdinaire_dureCeQuOnAttend() {
        assertThat(duree("08:00", "10:30").minutes()).isEqualTo(150);
        assertThat(duree("08:00", "10:30").heures()).isEqualTo(2.5);
        assertThat(duree("08:00", "10:30").exploitable()).isTrue();
    }

    @Test
    void unCoursDuSoirQuiFranchitMinuit_nEstJamaisNegatif() {
        // Le défaut d'origine, dans sa forme la plus nue.
        assertThat(duree("21:00", "00:00").heures()).isEqualTo(3.0);
        assertThat(duree("22:00", "01:30").heures()).isEqualTo(3.5);
        assertThat(duree("23:00", "01:00").heures()).isEqualTo(2.0);
    }

    @Test
    void aucuneDuree_nEstJamaisNegative() {
        // L'invariant qui compte : quelles que soient les deux heures, aucun appelant ne peut
        // plus recevoir un nombre négatif à additionner dans un total d'heures.
        for (int h = 0; h < 24; h++) {
            for (int k = 0; k < 24; k++) {
                DureeSeance.Duree d = duree(String.format("%02d:00", h), String.format("%02d:00", k));
                assertThat(d.minutes()).as("%02d:00 -> %02d:00", h, k).isNotNegative();
                assertThat(d.heures()).as("%02d:00 -> %02d:00", h, k).isNotNegative();
            }
        }
    }

    @Test
    void uneJourneeSaisieALEnvers_estRefusee_plutotQueLue() {
        // Intervertir les bornes d'une séance de durée D produit toujours 24 h − D. Pour toute
        // séance de six heures ou moins, cela fait au moins dix-huit heures : le plafond de
        // nuit écarte donc toutes les interversions plausibles, sans refuser un cours du soir.
        assertThat(duree("20:00", "10:00").exploitable()).isFalse();   // journee de 10 h a l'envers
        assertThat(duree("13:00", "08:00").exploitable()).isFalse();   // matinee de 5 h a l'envers
        assertThat(duree("10:00", "08:00").exploitable()).isFalse();
    }

    @Test
    void uneDureeNulle_neVeutRienDire() {
        assertThat(duree("08:00", "08:00").exploitable()).isFalse();
        assertThat(duree("08:00", "08:00").minutes()).isZero();
    }

    @Test
    void uneDureeAbsurdeEnAvant_estRefuseeAussi() {
        // Aucune ambiguïté arithmétique ici, mais un cours ne dure pas dix-sept heures. Payer
        // dix-sept heures sur une faute de frappe coûte autant que n'en payer aucune.
        assertThat(duree("06:00", "23:00").exploitable()).isFalse();
        // Et le plafond laisse passer une journée longue mais réelle.
        assertThat(duree("08:00", "20:00").exploitable()).isTrue();
        assertThat(duree("08:00", "20:00").heures()).isEqualTo(12.0);
    }

    @Test
    void uneHeureManquante_neFaitPasTomberLAppelant() {
        assertThat(DureeSeance.de(null, LocalTime.of(10, 0)).exploitable()).isFalse();
        assertThat(DureeSeance.de(LocalTime.of(8, 0), null).exploitable()).isFalse();
        assertThat(DureeSeance.de((ci.esatic.sigep.entity.Seance) null).exploitable()).isFalse();
    }

    @Test
    void aucuneInterversionPlausible_nePeutPasserPourUnCoursDuSoir() {
        // La propriété qui fait tenir tout le dispositif, et la seule qui mérite d'être figée.
        //
        // Intervertir les bornes d'une séance de durée D la fait lire 24 h − D. La plus longue
        // séance que le produit accepte dure PLAFOND_JOURNEE ; intervertie, elle se lit donc
        // 24 h − PLAFOND_JOURNEE, et c'est la plus COURTE lecture qu'une interversion puisse
        // produire. Tant que ce minimum dépasse le plafond de nuit, aucune interversion ne peut
        // se faire passer pour un cours du soir.
        long lectureLaPlusCourteDUneInterversion = 24 * 60 - DureeSeance.PLAFOND_JOURNEE_MINUTES;

        assertThat(lectureLaPlusCourteDUneInterversion)
                .as("relever le plafond de journee sans abaisser celui de nuit ouvrirait la porte")
                .isGreaterThan(DureeSeance.PLAFOND_NUIT_MINUTES);
    }
}
