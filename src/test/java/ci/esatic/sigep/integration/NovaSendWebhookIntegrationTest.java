package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.service.MobileMoneyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhook NovaSend : endpoint PUBLIC dont la seule barrière est la signature HMAC.
 * Une signature absente ou fausse ne doit JAMAIS déclencher de traitement de paiement.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.novasend.webhook-secret=secret-de-test")
class NovaSendWebhookIntegrationTest {

    private static final String SECRET = "secret-de-test";
    private static final String CORPS = "{\"reference\":\"ref-abc\",\"status\":\"success\",\"amount\":13000,\"currency\":\"XOF\"}";

    @MockBean private DataInitializer dataInitializer;
    /** Mocké : on teste la barrière de signature, pas la logique de crédit. */
    @MockBean private MobileMoneyService mobileMoneyService;

    @Autowired private MockMvc mockMvc;

    private String signer(String corps, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(corps.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void signatureValide_declencheLaVerification() throws Exception {
        mockMvc.perform(post("/api/paiement/novasend/webhook")
                        .contentType("application/json")
                        .header("X-Signature-Value", signer(CORPS, SECRET))
                        .content(CORPS))
                .andExpect(status().isOk());

        verify(mobileMoneyService).verifierEtCrediter("ref-abc");
    }

    @Test
    void signatureInvalide_estRefusee_etNeTraiteRien() throws Exception {
        mockMvc.perform(post("/api/paiement/novasend/webhook")
                        .contentType("application/json")
                        .header("X-Signature-Value", signer(CORPS, "mauvais-secret"))
                        .content(CORPS))
                .andExpect(status().isBadRequest());

        verify(mobileMoneyService, never()).verifierEtCrediter(anyString());
    }

    @Test
    void signatureAbsente_estRefusee() throws Exception {
        mockMvc.perform(post("/api/paiement/novasend/webhook")
                        .contentType("application/json")
                        .content(CORPS))
                .andExpect(status().isBadRequest());

        verify(mobileMoneyService, never()).verifierEtCrediter(anyString());
    }

    @Test
    void corpsAltere_invalideLaSignature() throws Exception {
        // Signature calculée sur le corps d'origine, mais corps modifié en transit (montant gonflé).
        String signature = signer(CORPS, SECRET);
        String altere = CORPS.replace("13000", "1");

        mockMvc.perform(post("/api/paiement/novasend/webhook")
                        .contentType("application/json")
                        .header("X-Signature-Value", signature)
                        .content(altere))
                .andExpect(status().isBadRequest());

        verify(mobileMoneyService, never()).verifierEtCrediter(anyString());
    }
}
