package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.service.MobileMoneyService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * Notifications de Jèko : une adresse PUBLIQUE dont la seule barrière est la signature. Une
 * signature absente ou fausse ne doit jamais déclencher la moindre vérification de paiement.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.jeko.webhook-secret=secret-jeko-de-test")
class JekoWebhookIntegrationTest {

    private static final String SECRET = "secret-jeko-de-test";
    private static final String CORPS = "{\"id\":\"txn_1\",\"amount\":{\"amount\":1300000,\"currency\":\"XOF\"},"
            + "\"status\":\"success\",\"transactionType\":\"payment\","
            + "\"transactionDetails\":{\"id\":\"pr-1\",\"reference\":\"JK-abc\"}}";

    @MockitoBean private DataInitializer dataInitializer;
    /** Remplacé : on éprouve la barrière de signature, pas la logique de crédit. */
    @MockitoBean private MobileMoneyService mobileMoneyService;
    @Autowired private MockMvc mockMvc;

    private String signer(String corps, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(corps.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void signatureValide_declencheLaVerification() throws Exception {
        mockMvc.perform(post("/api/paiement/jeko/webhook").contentType("application/json")
                        .header("Jeko-Event", "TRANSACTION_COMPLETED")
                        .header("Jeko-Signature", signer(CORPS, SECRET))
                        .content(CORPS))
                .andExpect(status().isOk());
        verify(mobileMoneyService).verifierEtCrediter("JK-abc");
    }

    @Test
    void signatureFausse_ouAbsente_estRefusee() throws Exception {
        mockMvc.perform(post("/api/paiement/jeko/webhook").contentType("application/json")
                        .header("Jeko-Signature", signer(CORPS, "mauvais-secret"))
                        .content(CORPS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/paiement/jeko/webhook").contentType("application/json")
                        .content(CORPS))
                .andExpect(status().isUnauthorized());
        verify(mobileMoneyService, never()).verifierEtCrediter(anyString());
    }

    @Test
    void corpsAltereEnRoute_invalideLaSignature() throws Exception {
        String signature = signer(CORPS, SECRET);
        mockMvc.perform(post("/api/paiement/jeko/webhook").contentType("application/json")
                        .header("Jeko-Signature", signature)
                        .content(CORPS.replace("1300000", "100")))
                .andExpect(status().isUnauthorized());
        verify(mobileMoneyService, never()).verifierEtCrediter(anyString());
    }

    @Test
    void unPaiementEtrangerASigep_ouUnAutreEvenement_nestPasTraite() throws Exception {
        String etranger = CORPS.replace("JK-abc", "COMMANDE-BOUTIQUE-12");
        mockMvc.perform(post("/api/paiement/jeko/webhook").contentType("application/json")
                        .header("Jeko-Signature", signer(etranger, SECRET))
                        .content(etranger))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/paiement/jeko/webhook").contentType("application/json")
                        .header("Jeko-Event", "ESCROW_HELD")
                        .header("Jeko-Signature", signer(CORPS, SECRET))
                        .content(CORPS))
                .andExpect(status().isOk());
        verify(mobileMoneyService, never()).verifierEtCrediter(anyString());
    }
}
