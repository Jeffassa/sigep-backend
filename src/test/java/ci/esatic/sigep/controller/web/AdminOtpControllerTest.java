package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Second facteur des administrateurs.
 *
 * <p>Ce que ces tests protègent : le code doit être le seul moyen d'ouvrir l'accès. Un test qui
 * se contenterait de vérifier « le bon code passe » laisserait filer l'essentiel — qu'un mauvais
 * code, un code périmé ou un acharnement ne l'ouvrent JAMAIS.
 */
class AdminOtpControllerTest {

    private static final String EMAIL = "admin@ecole.ci";

    private MailService mailService;
    private AdminOtpController controller;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        mailService = Mockito.mock(MailService.class);
        controller = new AdminOtpController(mailService,
                Mockito.mock(ci.esatic.sigep.service.JournalSecuriteService.class),
                Mockito.mock(ci.esatic.sigep.security.ClientIpResolver.class));
        session = new MockHttpSession();
    }

    /** Prépare une session et récupère le code réellement expédié (jamais exposé autrement). */
    private String preparerEtLireLeCode(boolean superAdmin) {
        AdminOtpController.preparer(session, EMAIL, mailService, superAdmin);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mailService, Mockito.atLeastOnce()).envoyerCodeOtpAdmin(eq(EMAIL), code.capture());
        return code.getValue();
    }

    private Authentication authAdmin(boolean superAdmin) {
        User u = User.builder().email(EMAIL).build();
        return new UsernamePasswordAuthenticationToken(u, null,
                List.of(new SimpleGrantedAuthority(superAdmin ? "ROLE_SUPER_ADMIN" : "ROLE_ADMIN")));
    }

    private boolean accesOuvert() {
        return Boolean.TRUE.equals(session.getAttribute(AdminOtpController.VERIFIED));
    }

    @Test
    void bonCode_ouvreLAcces_etRenvoieVersLEspaceAttendu() {
        String code = preparerEtLireLeCode(false);
        Model model = new ExtendedModelMap();

        String vue = controller.verifier(code, session, model, authAdmin(false), null);

        assertThat(vue).isEqualTo("redirect:/admin/dashboard");
        assertThat(accesOuvert()).isTrue();
    }

    @Test
    void superAdmin_estRenvoyeVersSonPropreEspace() {
        String code = preparerEtLireLeCode(true);

        String vue = controller.verifier(code, session, new ExtendedModelMap(), authAdmin(true), null);

        assertThat(vue).isEqualTo("redirect:/plateforme");
    }

    @Test
    void mauvaisCode_nOuvrePasLAcces() {
        String code = preparerEtLireLeCode(false);
        String faux = code.equals("000000") ? "111111" : "000000";
        Model model = new ExtendedModelMap();

        String vue = controller.verifier(faux, session, model, authAdmin(false), null);

        assertThat(vue).isEqualTo("admin/otp");
        assertThat(accesOuvert()).isFalse();
        assertThat(model.getAttribute("error")).isNotNull();
    }

    @Test
    void acharnement_verrouille_memeAvecLeBonCodeEnsuite() {
        String code = preparerEtLireLeCode(false);
        String faux = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            controller.verifier(faux, session, new ExtendedModelMap(), authAdmin(false), null);
        }
        // Le vrai code arrive trop tard : le quota de tentatives est épuisé.
        String vue = controller.verifier(code, session, new ExtendedModelMap(), authAdmin(false), null);

        assertThat(vue).isEqualTo("admin/otp");
        assertThat(accesOuvert()).isFalse();
    }

    @Test
    void renvoi_produitUnNouveauCode_etAnnuleLePrecedent() {
        String premier = preparerEtLireLeCode(false);

        controller.renvoyer(session, authAdmin(false), new ExtendedModelMap());

        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mailService, Mockito.times(2)).envoyerCodeOtpAdmin(eq(EMAIL), codes.capture());
        String second = codes.getAllValues().get(1);

        // L'ancien code ne doit plus rien ouvrir, sinon un renvoi multiplierait les secrets valides.
        controller.verifier(premier, session, new ExtendedModelMap(), authAdmin(false), null);
        assertThat(accesOuvert()).isFalse();

        controller.verifier(second, session, new ExtendedModelMap(), authAdmin(false), null);
        assertThat(accesOuvert()).isTrue();
    }

    @Test
    void renvoi_estBorne_pourNePasDevenirUnRobinetACourriels() {
        preparerEtLireLeCode(false);

        for (int i = 0; i < 3; i++) {
            controller.renvoyer(session, authAdmin(false), new ExtendedModelMap());
        }
        Model model = new ExtendedModelMap();
        controller.renvoyer(session, authAdmin(false), model);

        assertThat(model.getAttribute("error")).asString().contains("Trop de renvois");
        // 1 envoi initial + 3 renvois autorisés, le quatrième est refusé.
        Mockito.verify(mailService, Mockito.times(4)).envoyerCodeOtpAdmin(eq(EMAIL), anyString());
    }

    // ─── Code de secours ────────────────────────────────────────────────────
    // Raison d'etre : le second facteur depend d'un courriel, donc d'un service tiers.
    // Ces tests verifient qu'une panne d'envoi n'enferme personne dehors, sans pour autant
    // transformer ce code en porte derobee.

    private static final String CODE_SECOURS = "A7K2-9QMR-4XTZ-8WVN-3HCD";

    private void configurerCodeSecours(String codeEnClair) {
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "empreinteCodeSecours", sha256(codeEnClair));
    }

    private static String sha256(String v) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void codeDeSecours_ouvreLAcces_memeQuandAucunCourrielNaEteEnvoye() {
        configurerCodeSecours(CODE_SECOURS);
        // Aucun appel a preparer() : on simule la panne totale d'envoi. La session ne contient
        // donc AUCUN code attendu — c'est exactement la situation ou l'admin etait bloque.
        session.setAttribute("SIGEP_ADMIN_OTP_TARGET", "/admin/dashboard");

        String vue = controller.verifier(CODE_SECOURS, session, new ExtendedModelMap(), authAdmin(false), null);

        assertThat(vue).isEqualTo("redirect:/admin/dashboard");
        assertThat(accesOuvert()).isTrue();
        Mockito.verifyNoInteractions(mailService);
    }

    @Test
    void codeDeSecours_nonConfigure_nOuvreRien() {
        // Empreinte vide : la fonction n'existe pas, et surtout une chaine vide ne doit pas
        // se comparer favorablement a quoi que ce soit.
        preparerEtLireLeCode(false);

        controller.verifier("", session, new ExtendedModelMap(), authAdmin(false), null);
        assertThat(accesOuvert()).isFalse();

        controller.verifier(CODE_SECOURS, session, new ExtendedModelMap(), authAdmin(false), null);
        assertThat(accesOuvert()).isFalse();
    }

    @Test
    void codeDeSecours_resteSoumisAuQuotaDeTentatives() {
        configurerCodeSecours(CODE_SECOURS);
        preparerEtLireLeCode(false);

        for (int i = 0; i < 5; i++) {
            controller.verifier("MAUVAIS-CODE", session, new ExtendedModelMap(), authAdmin(false), null);
        }
        // Le quota epuise, meme le code de secours ne passe plus : sans cela, il offrirait
        // une surface de force brute que le code a 6 chiffres n'a pas.
        controller.verifier(CODE_SECOURS, session, new ExtendedModelMap(), authAdmin(false), null);

        assertThat(accesOuvert()).isFalse();
    }

    @Test
    void codeDeSecours_erroneEstRefuse() {
        configurerCodeSecours(CODE_SECOURS);

        controller.verifier("A7K2-9QMR-4XTZ-8WVN-0000", session, new ExtendedModelMap(), authAdmin(false), null);

        assertThat(accesOuvert()).isFalse();
    }

    @Test
    void sansAuthentification_aucunAccesPossible() {
        preparerEtLireLeCode(false);

        String vue = controller.verifier("000000", session, new ExtendedModelMap(), null, null);

        assertThat(vue).isEqualTo("redirect:/admin-login?error=true");
        assertThat(accesOuvert()).isFalse();
    }
}
