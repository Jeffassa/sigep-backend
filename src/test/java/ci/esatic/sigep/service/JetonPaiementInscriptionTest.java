package ci.esatic.sigep.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Le jeton qui ouvre la page de paiement d'une inscription ne s'ouvre que pour son établissement. */
class JetonPaiementInscriptionTest {

    private final JetonPaiementInscription jetons = new JetonPaiementInscription("secret-de-test-assez-long-pour-hmac");

    @Test
    void unJetonIntact_designeSonEtablissement() {
        assertThat(jetons.lire(jetons.creer(42L))).contains(42L);
    }

    @Test
    void changerLEtablissement_ouLaDate_invalideLeJeton() {
        String[] parties = jetons.creer(42L).split("\\.");
        assertThat(jetons.lire("43." + parties[1] + "." + parties[2])).isEmpty();
        assertThat(jetons.lire(parties[0] + "." + (Long.parseLong(parties[1]) + 999_999) + "." + parties[2])).isEmpty();
        assertThat(jetons.lire(parties[0] + "." + parties[1] + ".signature-inventee")).isEmpty();
    }

    @Test
    void unJetonExpire_nOuvrePlusRien() {
        assertThat(jetons.lire(jetons.creer(42L, Instant.now().minusSeconds(1)))).isEmpty();
    }

    @Test
    void unJetonSigneAvecUnAutreSecret_estRefuse() {
        String autre = new JetonPaiementInscription("un-autre-secret-de-serveur").creer(42L);
        assertThat(jetons.lire(autre)).isEmpty();
    }

    @Test
    void uneSaisieQuelconque_estRefusee_sansErreur() {
        assertThat(jetons.lire(null)).isEmpty();
        assertThat(jetons.lire("")).isEmpty();
        assertThat(jetons.lire("a.b.c")).isEmpty();
        assertThat(jetons.lire("x".repeat(500))).isEmpty();
    }
}
