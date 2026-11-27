package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.service.ZoneEmargement.Position;
import ci.esatic.sigep.service.ZoneEmargement.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ZoneEmargementTest {

    private static final double LAT = 5.359950;
    private static final double LON = -4.008260;
    /** Un millième de degré de latitude ≈ 111 m. */
    private static final double MILLIEME = 0.001;

    private static Etablissement zone(int rayon) {
        Etablissement e = Etablissement.builder().nom("École").slug("ecole").build();
        e.setZoneLatitude(LAT);
        e.setZoneLongitude(LON);
        e.setZoneRayonMetres(rayon);
        return e;
    }

    private static Position a(double dLat, Double precision) {
        return new Position(LAT + dLat, LON, precision, false);
    }

    @Test
    void laDistance_estCelleDUneCarte() {
        // Un degré de latitude fait environ 111,2 km partout.
        assertThat(ZoneEmargement.distanceMetres(0, 0, 1, 0)).isCloseTo(111_195, within(50.0));
        assertThat(ZoneEmargement.distanceMetres(LAT, LON, LAT, LON)).isZero();
    }

    @Test
    void sansZone_rienNEstControle_niExige() {
        Etablissement sans = Etablissement.builder().nom("École").slug("ecole").build();
        assertThat(ZoneEmargement.verifier(sans, null).verdict()).isEqualTo(Verdict.SANS_CONTROLE);
        assertThat(ZoneEmargement.verifier(null, a(1, 5.0)).verdict()).isEqualTo(Verdict.SANS_CONTROLE);
    }

    @Test
    void dansLeRayon_accepte_horsDuRayon_refuse() {
        assertThat(ZoneEmargement.verifier(zone(300), a(2 * MILLIEME, 10.0)).verdict()).isEqualTo(Verdict.ACCEPTE);
        var loin = ZoneEmargement.verifier(zone(300), a(10 * MILLIEME, 10.0));
        assertThat(loin.verdict()).isEqualTo(Verdict.HORS_ZONE);
        assertThat(loin.distanceMetres()).isBetween(1_100L, 1_125L);
    }

    @Test
    void laPrecisionAnnoncee_donneUneMarge_plafonnee() {
        // ~333 m du centre, rayon 300 : accepté si le téléphone annonce 50 m de précision...
        assertThat(ZoneEmargement.verifier(zone(300), a(3 * MILLIEME, 50.0)).verdict()).isEqualTo(Verdict.ACCEPTE);
        // ...mais une précision annoncée de 900 m ne couvre pas 1,1 km : la marge est plafonnée.
        assertThat(ZoneEmargement.verifier(zone(300), a(10 * MILLIEME, 900.0)).verdict()).isEqualTo(Verdict.HORS_ZONE);
    }

    @Test
    void positionAbsente_simulee_ouTropVague_estRefusee() {
        assertThat(ZoneEmargement.verifier(zone(300), null).verdict()).isEqualTo(Verdict.ABSENTE);
        assertThat(ZoneEmargement.verifier(zone(300), new Position(LAT, null, 5.0, false)).verdict())
                .isEqualTo(Verdict.ABSENTE);
        assertThat(ZoneEmargement.verifier(zone(300), new Position(LAT, LON, 5.0, true)).verdict())
                .isEqualTo(Verdict.SIMULEE);
        assertThat(ZoneEmargement.verifier(zone(300), a(0, 5_000.0)).verdict()).isEqualTo(Verdict.IMPRECISE);
    }

    @Test
    void lesCoordonneesSeLisentCommeUneCarteLesDonne() {
        assertThat(ZoneEmargement.lireCoordonnees("5.35995, -4.00826")).containsExactly(5.35995, -4.00826);
        assertThat(ZoneEmargement.lireCoordonnees(" 5.35995 -4.00826 ")).containsExactly(5.35995, -4.00826);
        assertThat(ZoneEmargement.lireCoordonnees("5.35995;-4.00826")).containsExactly(5.35995, -4.00826);
        assertThatThrownBy(() -> ZoneEmargement.lireCoordonnees("5,35995, -4,00826"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ZoneEmargement.lireCoordonnees("95, 10")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ZoneEmargement.lireCoordonnees("Abidjan")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ZoneEmargement.lireCoordonnees("")).isInstanceOf(IllegalArgumentException.class);
    }
}
