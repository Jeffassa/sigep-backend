package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.ConfigurationSso;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.StatutEtablissement;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.ConfigurationSsoRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.security.sso.ChiffrementSecret;
import ci.esatic.sigep.security.sso.DecouverteOidc;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * Les écrans du SSO, servis par la vraie chaîne de sécurité.
 *
 * <p><b>Pas de {@code @WithMockUser}.</b> Cette annotation pose le contexte de sécurité en amont
 * des filtres : une URL mal rattachée à sa chaîne continuerait de passer sous son couvert, et le
 * défaut n'apparaîtrait qu'en production. C'est un risque concret ici, parce que le SSO ajoute
 * quatre adresses — {@code /sso}, {@code /sso/**}, {@code /oauth2/authorization/**} et
 * {@code /login/oauth2/code/**} — qui, laissées à la chaîne d'API, seraient SANS ÉTAT : le
 * paramètre d'état et le nonce d'OpenID Connect vivent en session, et l'échange échouerait à
 * chaque fois sans que la cause soit visible.
 *
 * <p>La découverte OIDC est remplacée par une description figée : ces tests vérifient nos
 * décisions, pas la disponibilité d'un fournisseur sur internet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = "app.security.admin-otp.enabled=true")
class SsoWebTest {

    /**
     * Fournisseur d'identité décrit plutôt qu'appelé.
     *
     * <p>Sans cela, chaque test partirait interroger un serveur sur internet : il échouerait hors
     * ligne, et il mesurerait la disponibilité de ce serveur plutôt que notre code.
     */
    @TestConfiguration
    static class FournisseurDEssai {
        /** Ce que le fournisseur déclare comme point d'accès des jetons. Modifiable par test. */
        static String tokenUri = null;      // null = « issuer + /token », le cas normal
        /** true = le fournisseur ne répond pas du tout. */
        static boolean enPanne = false;

        @Bean
        @Primary
        DecouverteOidc decouverteDEssai() {
            return issuer -> {
                if (enPanne) {
                    throw new ci.esatic.sigep.security.sso.SsoIndisponibleException(
                            "Aucun fournisseur OpenID Connect n'a répondu à cette adresse.");
                }
                return ClientRegistration.withRegistrationId("essai")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizationUri(issuer + "/authorize")
                        .tokenUri(tokenUri != null ? tokenUri : issuer + "/token")
                        .jwkSetUri(issuer + "/jwks")
                        .userInfoUri(issuer + "/userinfo")
                        .userNameAttributeName("sub")
                        .issuerUri(issuer);
            };
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void remettreLeFournisseurANeuf() {
        FournisseurDEssai.tokenUri = null;
        FournisseurDEssai.enPanne = false;
    }

    @MockBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private ConfigurationSsoRepository configurationSsoRepository;
    @Autowired private ChiffrementSecret chiffrement;
    @Autowired private ci.esatic.sigep.security.sso.ConnexionSsoHandler handler;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    /**
     * Remet la session JPA dans l'état où une requête anonyme la trouverait en production.
     *
     * <p>Piège d'instrument, pas de produit. {@code TenantInterceptor} active le filtre Hibernate
     * sur la session liée à la requête, et {@code afterCompletion} n'efface que le contexte
     * ThreadLocal — pas le filtre. En production cela n'a aucun effet : chaque requête reçoit sa
     * propre session, ouverte puis fermée. Ici, {@code @Transactional} fait partager <b>une
     * seule</b> session à tout le test, si bien que le filtre posé par un appel d'administration
     * survit à l'appel anonyme qui suit — et la découverte, qui doit voir tous les établissements,
     * n'en voit plus qu'un.
     */
    private void oublierLeFiltreTenant() {
        entityManager.unwrap(org.hibernate.Session.class).disableFilter("tenantFilter");
        ci.esatic.sigep.tenant.TenantContext.clear();
    }

    private Etablissement dernierEtablissement;

    /** Une session d'administrateur d'un établissement au plan demandé. */
    private MockHttpSession session(Plan plan) {
        Etablissement etablissement = etablissementRepository.save(Etablissement.builder()
                .nom("Ecole " + plan).slug("ecole-sso-web-" + System.nanoTime())
                .plan(plan).statut(StatutEtablissement.VALIDE).build());
        dernierEtablissement = etablissement;
        Role role = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        User admin = userRepository.save(User.builder()
                .email("admin.sso." + System.nanoTime() + "@ecole.ci")
                .password(passwordEncoder.encode("x"))
                .roles(Set.of(role)).etablissement(etablissement).build());

        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                admin, "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);
        // Le second facteur est actif dans cette classe : sans ce drapeau, chaque page
        // d'administration répondrait 302 vers /admin-otp et l'on ne testerait rien.
        session.setAttribute(
                ci.esatic.sigep.controller.web.AdminOtpController.VERIFIED, true);
        return session;
    }

    // ───────────────────────── L'écran de configuration ─────────────────────────

    @Test
    void lEcran_seRend_etMontreLUrlDeRetourAColler() throws Exception {
        var reponse = mockMvc.perform(get("/admin/sso").session(session(Plan.ENTERPRISE)))
                .andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(200);
        String html = reponse.getContentAsString();
        // Cette adresse décide de la réussite au caractère près, et le message du fournisseur
        // (« redirect_uri_mismatch ») ne dit jamais quelle valeur il attendait.
        assertThat(html).contains("/login/oauth2/code/" + dernierEtablissement.getSlug());
        assertThat(html).contains("/sso/" + dernierEtablissement.getSlug());
    }

    @Test
    void lEcran_expliqueLeVerrou_auxPlansQuiNOntPasLOption() throws Exception {
        var reponse = mockMvc.perform(get("/admin/sso").session(session(Plan.PRO)))
                .andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(200);
        assertThat(reponse.getContentAsString()).contains("plan Enterprise");
    }

    @Test
    void enregistrer_estRefuse_auPlanQuiNAPasLOption() throws Exception {
        // L'interface masque le formulaire, mais l'adresse se devine. Un verrou qui ne vit que
        // dans l'affichage ne verrouille rien.
        var reponse = mockMvc.perform(post("/admin/sso").session(session(Plan.PRO)).with(csrf())
                        .param("fournisseur", "Annuaire")
                        .param("issuer", "https://203.0.113.10/idp")
                        .param("clientId", "client")
                        .param("clientSecret", "secret")
                        .param("actif", "true"))
                .andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(403);
        assertThat(configurationSsoRepository.count()).isZero();
    }

    @Test
    void lEcran_estInaccessible_sansSession() throws Exception {
        var reponse = mockMvc.perform(get("/admin/sso")).andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(302);
        assertThat(reponse.getRedirectedUrl()).contains("/admin-login");
    }

    @Test
    void enregistrer_chiffreLeSecret_etNeLeReaffichejamais() throws Exception {
        MockHttpSession session = session(Plan.ENTERPRISE);
        mockMvc.perform(post("/admin/sso").session(session).with(csrf())
                .param("fournisseur", "Annuaire d'essai")
                .param("issuer", "https://203.0.113.10/idp")
                .param("clientId", "client-essai")
                .param("clientSecret", "le-secret-tres-confidentiel")
                .param("domaines", "@ECOLE.CI, ecole.ci")
                .param("actif", "true"));

        ConfigurationSso config = configurationSsoRepository
                .findByEtablissementId(dernierEtablissement.getId()).orElseThrow();
        // Une sauvegarde de base qui fuite ne doit livrer aucun secret utilisable.
        assertThat(config.getClientSecret()).isNotEqualTo("le-secret-tres-confidentiel");
        assertThat(chiffrement.dechiffrer(config.getClientSecret()))
                .isEqualTo("le-secret-tres-confidentiel");
        // Saisie naturelle (« @ECOLE.CI, ecole.ci ») ramenée à un seul domaine exploitable.
        assertThat(config.getDomaines()).isEqualTo("ecole.ci");

        String html = mockMvc.perform(get("/admin/sso").session(session))
                .andReturn().getResponse().getContentAsString();
        // Il n'est jamais réaffiché, pas même à celui qui vient de le saisir : l'écran n'est pas
        // un coffre où l'on viendrait relire ses secrets.
        assertThat(html).doesNotContain("le-secret-tres-confidentiel");
        assertThat(html).doesNotContain(config.getClientSecret());
    }

    @Test
    void unSecretLaisseVide_conserveCeluiDejaEnregistre() throws Exception {
        MockHttpSession session = session(Plan.ENTERPRISE);
        mockMvc.perform(post("/admin/sso").session(session).with(csrf())
                .param("fournisseur", "Annuaire").param("issuer", "https://203.0.113.10/idp")
                .param("clientId", "client").param("clientSecret", "secret-initial")
                .param("actif", "true"));

        // Corriger un domaine ne doit pas obliger à retrouver un secret qu'on n'a plus —
        // sans quoi la seule issue serait d'en régénérer un chez le fournisseur.
        mockMvc.perform(post("/admin/sso").session(session).with(csrf())
                .param("fournisseur", "Annuaire").param("issuer", "https://203.0.113.10/idp")
                .param("clientId", "client").param("clientSecret", "")
                .param("domaines", "ecole.ci").param("actif", "true"));

        ConfigurationSso config = configurationSsoRepository
                .findByEtablissementId(dernierEtablissement.getId()).orElseThrow();
        assertThat(chiffrement.dechiffrer(config.getClientSecret())).isEqualTo("secret-initial");
        assertThat(config.getDomaines()).isEqualTo("ecole.ci");
    }

    @Test
    void uneAdresseInterne_estRefusee_etRienNEstEnregistre() throws Exception {
        MockHttpSession session = session(Plan.ENTERPRISE);
        var reponse = mockMvc.perform(post("/admin/sso").session(session).with(csrf())
                        .param("fournisseur", "Annuaire")
                        // L'adresse des métadonnées d'hébergeur : c'est NOTRE serveur qui irait
                        // la chercher, et il en rapporterait ses propres identifiants.
                        .param("issuer", "https://169.254.169.254/latest/meta-data")
                        .param("clientId", "client").param("clientSecret", "secret")
                        .param("actif", "true"))
                .andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(302);
        assertThat(configurationSsoRepository.count()).isZero();
    }

    @Test
    void uneAdresseEnClair_estRefusee() throws Exception {
        MockHttpSession session = session(Plan.ENTERPRISE);
        mockMvc.perform(post("/admin/sso").session(session).with(csrf())
                .param("fournisseur", "Annuaire").param("issuer", "http://idp.exemple.ci")
                .param("clientId", "client").param("clientSecret", "secret")
                .param("actif", "true"));

        // Un échange OpenID Connect en clair expose le jeton d'identité sur le réseau.
        assertThat(configurationSsoRepository.count()).isZero();
    }

    @Test
    void unPointDAccesInterne_declareParLeFournisseur_estRefuse() throws Exception {
        MockHttpSession session = session(Plan.ENTERPRISE);
        mockMvc.perform(post("/admin/sso").session(session).with(csrf())
                .param("fournisseur", "Annuaire").param("issuer", "https://203.0.113.10/idp")
                .param("clientId", "client").param("clientSecret", "secret")
                .param("actif", "true"));

        // Le document de découverte est rédigé par le FOURNISSEUR : c'est lui qui nomme le point
        // d'accès des jetons. Vérifier la seule adresse de l'émetteur ne protégeait donc rien —
        // et ce point d'accès-là reçoit le secret client dans son en-tête d'authentification.
        // En clair, sur du HTTP, vers une machine de notre réseau interne.
        FournisseurDEssai.tokenUri = "http://169.254.169.254/latest/api/token";

        var reponse = mockMvc.perform(
                        get("/oauth2/authorization/" + dernierEtablissement.getSlug()))
                .andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(302);
        assertThat(reponse.getRedirectedUrl()).isEqualTo("/admin-login?sso_error=inconnu");
    }

    @Test
    void couperLeSso_resteroPossible_quandLeFournisseurEstEnPanne() throws Exception {
        MockHttpSession session = session(Plan.ENTERPRISE);
        mockMvc.perform(post("/admin/sso").session(session).with(csrf())
                .param("fournisseur", "Annuaire").param("issuer", "https://203.0.113.10/idp")
                .param("clientId", "client").param("clientSecret", "secret")
                .param("actif", "true"));

        // Le fournisseur tombe. C'est EXACTEMENT le moment où l'on veut décocher « Activer »
        // pour que les administrateurs reprennent la main par mot de passe.
        FournisseurDEssai.enPanne = true;

        mockMvc.perform(post("/admin/sso").session(session).with(csrf())
                .param("fournisseur", "Annuaire").param("issuer", "https://203.0.113.10/idp")
                .param("clientId", "client").param("clientSecret", "")
                .param("actif", "false"));

        // Exiger que le fournisseur réponde pour avoir le droit de le débrancher rendait
        // l'interrupteur inutilisable au seul moment où il sert.
        assertThat(configurationSsoRepository.findByEtablissementId(dernierEtablissement.getId()))
                .get().extracting(ConfigurationSso::isActif).isEqualTo(false);
    }

    // ───────────────────────── La porte d'entrée publique ─────────────────────────

    @Test
    void laPageDeDecouverte_estPublique() throws Exception {
        var reponse = mockMvc.perform(get("/sso")).andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(200);
        assertThat(reponse.getContentAsString()).contains("Votre organisation");
    }

    @Test
    void unLienDirect_meneAuFournisseur() throws Exception {
        MockHttpSession session = session(Plan.ENTERPRISE);
        mockMvc.perform(post("/admin/sso").session(session).with(csrf())
                .param("fournisseur", "Annuaire").param("issuer", "https://203.0.113.10/idp")
                .param("clientId", "client").param("clientSecret", "secret")
                .param("actif", "true"));

        var reponse = mockMvc.perform(get("/sso/" + dernierEtablissement.getSlug()))
                .andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(302);
        assertThat(reponse.getRedirectedUrl())
                .isEqualTo("/oauth2/authorization/" + dernierEtablissement.getSlug());
    }

    @Test
    void unLienDirect_versUnEtablissementSansSso_ramenAuMotDePasse() throws Exception {
        var reponse = mockMvc.perform(get("/sso/etablissement-fantome"))
                .andReturn().getResponse();

        // Une impasse serait pire qu'un retour en arrière : quelqu'un qui suit un lien périmé
        // doit pouvoir entrer malgré tout.
        assertThat(reponse.getStatus()).isEqualTo(302);
        assertThat(reponse.getRedirectedUrl()).isEqualTo("/admin-login?sso_error=inconnu");
    }

    @Test
    void laDecouverte_neRevelePasQuiEstClient() throws Exception {
        var reponse = mockMvc.perform(post("/sso").with(csrf())
                        .param("identifiant", "quelquun@une-ecole-inexistante.ci"))
                .andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(200);
        String html = reponse.getContentAsString();
        // Même phrase pour « inconnu », « sans SSO », « plan insuffisant » et « suspendu » :
        // sinon la page devient un annuaire de nos clients, interrogeable par n'importe qui.
        // Sans apostrophe dans l'attendu : Thymeleaf echappe « ' » en « &#39; », si bien
        // qu'une assertion ecrite telle qu'on lit la phrase echouerait sans rien dire du code.
        assertThat(html).contains("Aucune connexion par votre organisation");
    }

    @Test
    void laDecouverte_retrouveLEtablissementParLeDomaine() throws Exception {
        MockHttpSession session = session(Plan.ENTERPRISE);
        mockMvc.perform(post("/admin/sso").session(session).with(csrf())
                .param("fournisseur", "Annuaire").param("issuer", "https://203.0.113.10/idp")
                .param("clientId", "client").param("clientSecret", "secret")
                .param("domaines", "ecole-connue.ci").param("actif", "true"));

        oublierLeFiltreTenant();
        var reponse = mockMvc.perform(post("/sso").with(csrf())
                        .param("identifiant", "quelquun@ecole-connue.ci"))
                .andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(302);
        assertThat(reponse.getRedirectedUrl())
                .isEqualTo("/oauth2/authorization/" + dernierEtablissement.getSlug());
    }

    @Test
    void leDepart_construitUneVraieDemandeDAutorisation() throws Exception {
        MockHttpSession session = session(Plan.ENTERPRISE);
        mockMvc.perform(post("/admin/sso").session(session).with(csrf())
                .param("fournisseur", "Annuaire").param("issuer", "https://203.0.113.10/idp")
                .param("clientId", "client-de-lecole").param("clientSecret", "secret")
                .param("actif", "true"));

        // Le saut suivant, celui que le navigateur fait réellement. Les tests précédents
        // s'arrêtaient à notre redirection interne ; celui-ci prouve que l'inscription est bel
        // et bien fabriquée À PARTIR DE LA BASE et branchée sur la chaîne de sécurité.
        var reponse = mockMvc.perform(get("/oauth2/authorization/" + dernierEtablissement.getSlug())
                        .session(session)).andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(302);
        String versLeFournisseur = reponse.getRedirectedUrl();
        assertThat(versLeFournisseur).startsWith("https://203.0.113.10/idp/authorize");
        assertThat(versLeFournisseur).contains("client_id=client-de-lecole");
        // L'URL de retour porte le slug : un code d'autorisation émis pour un établissement ne
        // peut pas être présenté sur l'adresse d'un autre.
        assertThat(versLeFournisseur)
                .contains("/login/oauth2/code/" + dernierEtablissement.getSlug());
        // state et nonce : les deux valeurs que le navigateur doit retrouver au retour. Elles
        // vivent en session — c'est la raison pour laquelle ces adresses sont captées par la
        // chaîne d'administration, qui a des sessions, et non par celle des API, qui n'en a pas.
        assertThat(versLeFournisseur).contains("state=").contains("nonce=");
        // Le strict nécessaire : chaque autorisation demandée en trop est une autorisation
        // qu'un jeton volé emporterait avec lui.
        assertThat(java.net.URLDecoder.decode(versLeFournisseur,
                java.nio.charset.StandardCharsets.UTF_8))
                .contains("scope=openid email profile");
    }

    @Test
    void leDepart_estRefuse_pourUnEtablissementSansOption() throws Exception {
        MockHttpSession session = session(Plan.ENTERPRISE);
        mockMvc.perform(post("/admin/sso").session(session).with(csrf())
                .param("fournisseur", "Annuaire").param("issuer", "https://203.0.113.10/idp")
                .param("clientId", "client").param("clientSecret", "secret")
                .param("actif", "true"));
        // L'établissement redescend d'offre, sa configuration reste en base.
        dernierEtablissement.setPlan(Plan.PRO);
        etablissementRepository.save(dernierEtablissement);

        var reponse = mockMvc.perform(
                        get("/oauth2/authorization/" + dernierEtablissement.getSlug()))
                .andReturn().getResponse();

        // Le verrou doit tenir sur le chemin de connexion lui-même : l'adresse se devine, et
        // l'écran de configuration n'est pas sur ce chemin.
        // Le registre ne rend aucune inscription. Deux attentes, et la seconde n'est pas
        // cosmétique : d'abord aucune redirection vers le fournisseur — le verrou tient — et
        // ensuite un retour vers la connexion par mot de passe plutôt qu'une page « erreur du
        // serveur », qui est ce que Spring produit si on le laisse faire. Un lien SSO mis en
        // favori survit à un changement d'offre.
        assertThat(reponse.getStatus()).isEqualTo(302);
        assertThat(reponse.getRedirectedUrl()).isEqualTo("/admin-login?sso_error=inconnu");
    }

    // ───────── Le second facteur, avec l'interrupteur REELLEMENT actif ─────────
    //
    // Le profil de test désactive le code à six chiffres pour toutes les autres classes :
    // l'arbitrage « le SSO le remplace-t-il ? » y serait donc trivialement vrai, et le test
    // ne prouverait rien. Cette classe le rallume (cf. @TestPropertySource).

    @Test
    void parDefaut_leSso_remplaceNotreCodeASixChiffres() throws Exception {
        var montage = montageSso(false);

        var reponse = new org.springframework.mock.web.MockHttpServletResponse();
        handler.onAuthenticationSuccess(montage.requete(), reponse, montage.jeton());

        // C'est la promesse commerciale du SSO : l'établissement applique SA politique de
        // second facteur chez son fournisseur. Lui réclamer un code par courriel par-dessus
        // reviendrait à ne lui avoir rien vendu.
        assertThat(reponse.getRedirectedUrl()).isEqualTo("/admin/dashboard");
        assertThat(montage.requete().getSession()
                .getAttribute(ci.esatic.sigep.controller.web.AdminOtpController.VERIFIED))
                .isEqualTo(true);
    }

    @Test
    void siLEtablissementLExige_leCodeASixChiffresResteDemande() throws Exception {
        var montage = montageSso(true);

        var reponse = new org.springframework.mock.web.MockHttpServletResponse();
        handler.onAuthenticationSuccess(montage.requete(), reponse, montage.jeton());

        // La case existe pour l'établissement dont le fournisseur n'impose aucun second
        // facteur : la décision lui appartient, pas à nous.
        assertThat(reponse.getRedirectedUrl()).isEqualTo("/admin-otp");
        assertThat(montage.requete().getSession()
                .getAttribute(ci.esatic.sigep.controller.web.AdminOtpController.VERIFIED))
                .isNotEqualTo(true);
    }

    private record MontageSso(org.springframework.mock.web.MockHttpServletRequest requete,
                              org.springframework.security.oauth2.client.authentication
                                      .OAuth2AuthenticationToken jeton) {}

    /** Un établissement Enterprise configuré, un administrateur connu, et le retour du fournisseur. */
    private MontageSso montageSso(boolean exigerOtp) {
        Etablissement e = etablissementRepository.save(Etablissement.builder()
                .nom("Ecole OTP").slug("ecole-sso-otp-" + System.nanoTime())
                .plan(Plan.ENTERPRISE).statut(StatutEtablissement.VALIDE).build());
        configurationSsoRepository.save(ConfigurationSso.builder()
                .etablissementId(e.getId()).fournisseur("Annuaire")
                .issuer("https://203.0.113.10/idp").clientId("client")
                .clientSecret(chiffrement.chiffrer("secret"))
                .actif(true).exigerOtp(exigerOtp).build());
        Role role = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        String email = "chef.otp." + System.nanoTime() + "@ecole.ci";
        userRepository.save(User.builder().email(email).password(passwordEncoder.encode("x"))
                .roles(Set.of(role)).etablissement(e).build());

        var idToken = org.springframework.security.oauth2.core.oidc.OidcIdToken
                .withTokenValue("jeton").issuer("https://203.0.113.10/idp").subject("sujet")
                .claim("email", email).claim("email_verified", true)
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(300)).build();
        var oidc = new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken);
        var requete = new org.springframework.mock.web.MockHttpServletRequest(
                "GET", "/login/oauth2/code/" + e.getSlug());
        requete.setSession(new MockHttpSession());
        SecurityContextHolder.clearContext();
        return new MontageSso(requete,
                new org.springframework.security.oauth2.client.authentication
                        .OAuth2AuthenticationToken(oidc, oidc.getAuthorities(), e.getSlug()));
    }

    @Test
    void unDomaineRevendiqueParDeuxEtablissements_neMeneNullePart() throws Exception {
        // Rien ne nous permet de vérifier qu'un établissement possède le domaine qu'il inscrit.
        MockHttpSession premiere = session(Plan.ENTERPRISE);
        mockMvc.perform(post("/admin/sso").session(premiere).with(csrf())
                .param("fournisseur", "Annuaire A").param("issuer", "https://203.0.113.10/idp")
                .param("clientId", "client-a").param("clientSecret", "secret")
                .param("domaines", "domaine-dispute.ci").param("actif", "true"));

        MockHttpSession seconde = session(Plan.ENTERPRISE);
        mockMvc.perform(post("/admin/sso").session(seconde).with(csrf())
                .param("fournisseur", "Annuaire B").param("issuer", "https://203.0.113.10/idp")
                .param("clientId", "client-b").param("clientSecret", "secret")
                .param("domaines", "domaine-dispute.ci").param("actif", "true"));

        oublierLeFiltreTenant();
        var reponse = mockMvc.perform(post("/sso").with(csrf())
                        .param("identifiant", "agent@domaine-dispute.ci"))
                .andReturn().getResponse();

        // Rendre le premier venu enverrait les agents d'une école vers le fournisseur d'identité
        // d'une autre, qui verrait passer leurs adresses et leur présenterait sa propre page de
        // connexion. Aucune session ne s'ouvrirait, mais l'hameçonnage n'en demande pas tant.
        assertThat(reponse.getStatus()).isEqualTo(200);
        assertThat(reponse.getContentAsString()).contains("Aucune connexion par votre organisation");
    }

    // ───────────────────────── Le message de refus ─────────────────────────

    @Test
    void unMotifConnu_estTraduitEnFrancais() throws Exception {
        String html = mockMvc.perform(get("/admin-login?sso_error=domaine"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("pas à un domaine autorisé");
    }

    @Test
    void unMessageForge_neSAffichePas() throws Exception {
        // Un lien envoyé à une victime : le texte s'afficherait sur NOTRE page de connexion,
        // dans notre charte, et paraîtrait venir de nous. C'est de l'hameçonnage clé en main.
        String html = mockMvc.perform(
                        get("/admin-login?sso_error=Votre+compte+est+compromis,+appelez+le+07+00+00"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("appelez le 07");
        assertThat(html).contains("Utilisez votre mot de passe, ou contactez");
    }
}
