package ci.esatic.sigep.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Garde-fou de démarrage : hors profil dev, on refuse toute clé JWT faible
 * (clé de dev connue, vide, non base64, < 256 bits, ou access == QR).
 */
class SecuriteDemarrageGuardTest {

    // Clés de test valides (base64, ≥ 32 octets décodés), distinctes.
    private static final String SECRET_OK_1 = "dGVzdC1zZWNyZXQta2V5LWZvci1zaWdlcC10ZXN0cy1vbmx5LTI2Y2hhcnM=";
    private static final String SECRET_OK_2 = "dGVzdC1xci1zZWNyZXQta2V5LWZvci1zaWdlcC10ZXN0cy1vbmx5LTI2Yg==";
    // Clé de dev publique blacklistée (application-dev.yml).
    private static final String SECRET_DEV = "ZGV2LXNpZ2VwLWp3dC1zZWNyZXQta2V5LW5vdC1mb3ItcHJvZHVjdGlvbi0yMDI2";

    private Environment env(String... profils) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(profils);
        return env;
    }

    private void verifier(Environment env, String jwt, String qr) {
        verifier(env, jwt, qr, true);   // HTTPS supposé activé : on isole les vérifs de secrets
    }

    private void verifier(Environment env, String jwt, String qr, boolean requireHttps) {
        new SecuriteDemarrageGuard(env, jwt, qr, requireHttps).verifierSecrets();
    }

    @Test
    void prodSansHttps_refuse() {
        assertThatThrownBy(() -> verifier(env("prod"), SECRET_OK_1, SECRET_OK_2, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("require-https");
    }

    @Test
    void clesFortesEtDistinctes_horsDev_demarre() {
        assertThatCode(() -> verifier(env("prod"), SECRET_OK_1, SECRET_OK_2))
                .doesNotThrowAnyException();
    }

    @Test
    void profilDev_toleTouteCle() {
        // En dev, même la clé de dev publique est acceptée (pas de levée).
        assertThatCode(() -> verifier(env("dev"), SECRET_DEV, SECRET_DEV))
                .doesNotThrowAnyException();
    }

    @Test
    void cleDevConnue_horsDev_refuse() {
        assertThatThrownBy(() -> verifier(env("prod"), SECRET_DEV, SECRET_OK_2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DÉVELOPPEMENT");
    }

    @Test
    void cleVide_refuse() {
        assertThatThrownBy(() -> verifier(env("prod"), "", SECRET_OK_2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absent");
    }

    @Test
    void cleNonBase64_refuse() {
        // 'dev_secret_change_in_prod' (ancien repli docker) contient '_' → base64 invalide.
        assertThatThrownBy(() -> verifier(env("prod"), "dev_secret_change_in_prod", SECRET_OK_2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void cleTropCourte_refuse() {
        // 'c2hvcnQ=' = "short" (5 octets) < 32.
        assertThatThrownBy(() -> verifier(env("prod"), "c2hvcnQ=", SECRET_OK_2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("octets");
    }

    @Test
    void clesIdentiques_refuse() {
        assertThatThrownBy(() -> verifier(env("prod"), SECRET_OK_1, SECRET_OK_1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identiques");
    }

    @Test
    void minimum256Bits_estBienDe32Octets() {
        assertThat(32).isEqualTo(256 / 8);
    }
}
