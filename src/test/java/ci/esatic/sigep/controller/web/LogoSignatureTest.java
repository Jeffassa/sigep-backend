package ci.esatic.sigep.controller.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Le logo n'est accepté que si ses premiers octets correspondent au format annoncé. */
class LogoSignatureTest {

    private static byte[] octets(int... v) {
        byte[] b = new byte[16];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    @Test
    void lesVraisFormats_passent() {
        assertThat(LogoEtablissementController.signatureConforme(
                octets(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A), "image/png")).isTrue();
        assertThat(LogoEtablissementController.signatureConforme(
                octets(0xFF, 0xD8, 0xFF, 0xE0), "image/jpeg")).isTrue();
        assertThat(LogoEtablissementController.signatureConforme(
                octets('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'), "image/webp")).isTrue();
    }

    @Test
    void unePageHtmlDeguiseeEnImage_estRefusee() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        assertThat(LogoEtablissementController.signatureConforme(html, "image/png")).isFalse();
        assertThat(LogoEtablissementController.signatureConforme(html, "image/jpeg")).isFalse();
        assertThat(LogoEtablissementController.signatureConforme(
                octets(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A), "image/jpeg")).isFalse();
    }
}
