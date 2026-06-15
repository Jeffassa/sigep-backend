package ci.esatic.sigep.security;

import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    // Clés encodées en Base64 (chacune >= 32 octets décodés pour HS256)
    private static final String TEST_SECRET = Base64.getEncoder()
            .encodeToString("test-secret-key-for-sigep-unit-tests-only".getBytes(StandardCharsets.UTF_8));
    private static final String TEST_QR_SECRET = Base64.getEncoder()
            .encodeToString("test-qr-secret-key-for-sigep-unit-tests-only".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", TEST_SECRET);
        ReflectionTestUtils.setField(jwtService, "qrSecretKey", TEST_QR_SECRET);
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", 86400000L);
    }

    private User buildUser(String email) {
        Role role = new Role(1, ERole.ROLE_ENSEIGNANT);
        return User.builder()
                .id(1L)
                .email(email)
                .password("encoded")
                .roles(Set.of(role))
                .build();
    }

    // --- Tests token utilisateur ---

    @Test
    void generateToken_devraitProduireUnTokenNonVide() {
        String token = jwtService.generateToken(buildUser("prof@esatic.ci"));
        assertThat(token).isNotBlank();
    }

    @Test
    void extractUsername_devraitRetournerEmail() {
        User user = buildUser("directeur@esatic.ci");
        String token = jwtService.generateToken(user);
        assertThat(jwtService.extractUsername(token)).isEqualTo("directeur@esatic.ci");
    }

    @Test
    void isTokenValid_devraitRetournerTruePourBonUser() {
        User user = buildUser("prof@esatic.ci");
        String token = jwtService.generateToken(user);
        assertThat(jwtService.isTokenValid(token, user)).isTrue();
    }

    @Test
    void isTokenValid_devraitRetournerFalsePourMauvaisUser() {
        User user = buildUser("prof@esatic.ci");
        User autre = buildUser("imposteur@esatic.ci");
        String token = jwtService.generateToken(user);
        assertThat(jwtService.isTokenValid(token, autre)).isFalse();
    }

    // --- Tests token QR ---

    @Test
    void generateQrToken_devraitEtreValidePourLaBonneSalle() {
        String token = jwtService.generateQrToken("SALLE-A1", 30_000L);
        assertThat(token).isNotBlank();
        assertThat(jwtService.isQrTokenValid(token, "SALLE-A1")).isTrue();
    }

    @Test
    void isQrTokenValid_devraitRetournerFalsePourMauvaiseSalle() {
        String token = jwtService.generateQrToken("SALLE-A1", 30_000L);
        assertThat(jwtService.isQrTokenValid(token, "SALLE-B2")).isFalse();
    }

    @Test
    void isQrTokenValid_devraitRetournerFalsePourTokenExpire() throws InterruptedException {
        String token = jwtService.generateQrToken("SALLE-A1", 1L); // expire en 1 ms
        Thread.sleep(20);
        assertThat(jwtService.isQrTokenValid(token, "SALLE-A1")).isFalse();
    }

    @Test
    void isQrTokenValid_devraitRejeterUnTokenUtilisateurStandard() {
        // Un JWT utilisateur (signé avec jwt.secret) ne doit pas être accepté comme QR
        User user = buildUser("prof@esatic.ci");
        String userToken = jwtService.generateToken(user);
        assertThat(jwtService.isQrTokenValid(userToken, "prof@esatic.ci")).isFalse();
    }
}
