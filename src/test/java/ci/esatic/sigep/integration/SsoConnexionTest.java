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
import ci.esatic.sigep.controller.web.AdminOtpController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La décision qui porte tout le SSO : « ce retour de fournisseur d'identité ouvre-t-il une
 * porte ? ».
 *
 * <p>Ces tests appellent le gestionnaire exactement comme Spring Security l'appelle, avec un
 * jeton d'identité fabriqué. C'est volontairement l'angle le plus proche de l'attaque : on y
 * dicte ce que le fournisseur affirme — une adresse non vérifiée, une adresse appartenant à un
 * autre établissement, une adresse d'un compte désactivé — et l'on observe ce que SIGEP en fait.
 *
 * <p>Chaque cas de refus correspond à une façon crédible de se tromper ou de tricher :
 * <ul>
 *   <li>un fournisseur mal configuré qui accepte des comptes personnels ;</li>
 *   <li>un établissement qui tente d'atteindre les données d'un autre par son propre annuaire ;</li>
 *   <li>un agent parti, désactivé chez nous mais pas chez lui ;</li>
 *   <li>un abonnement expiré qui n'aurait fermé que la porte du mot de passe.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SsoConnexionTest {

    @MockBean private DataInitializer dataInitializer;

    @Autowired private ci.esatic.sigep.security.sso.ConnexionSsoHandler handler;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private ConfigurationSsoRepository configurationSsoRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ChiffrementSecret chiffrement;

    // ───────────────────────────── Montage ─────────────────────────────

    private Etablissement etablissement(Plan plan) {
        return etablissementRepository.save(Etablissement.builder()
                .nom("Ecole SSO").slug("ecole-sso-" + System.nanoTime())
                .plan(plan).statut(StatutEtablissement.VALIDE).build());
    }

    private ConfigurationSso configurer(Etablissement e, String domaines) {
        return configurationSsoRepository.save(ConfigurationSso.builder()
                .etablissementId(e.getId())
                .fournisseur("Annuaire d'essai")
                .issuer("https://203.0.113.10/idp")
                .clientId("client-essai")
                .clientSecret(chiffrement.chiffrer("secret-essai"))
                .domaines(domaines)
                .actif(true)
                .build());
    }

    private User compte(Etablissement e, String email, ERole role, boolean actif) {
        Role r = roleRepository.findByName(role)
                .orElseGet(() -> roleRepository.save(new Role(null, role)));
        return userRepository.save(User.builder()
                .email(email).password(passwordEncoder.encode("x"))
                .roles(Set.of(r)).etablissement(e).enabled(actif).build());
    }

    /** Ce que le fournisseur d'identité affirme au retour. */
    private OAuth2AuthenticationToken retourDuFournisseur(String slug, String email,
                                                          boolean adresseVerifiee) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("jeton-identite")
                .issuer("https://203.0.113.10/idp")
                .subject("sujet-" + email)
                .claim("email", email)
                .claim("email_verified", adresseVerifiee)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        OidcUser oidc = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken);
        return new OAuth2AuthenticationToken(oidc, oidc.getAuthorities(), slug);
    }

    /** Joue le retour et rend la redirection obtenue. */
    private String jouer(OAuth2AuthenticationToken jeton, MockHttpServletRequest requete)
            throws Exception {
        MockHttpServletResponse reponse = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
        handler.onAuthenticationSuccess(requete, reponse, jeton);
        return reponse.getRedirectedUrl();
    }

    private MockHttpServletRequest requete(String slug) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/login/oauth2/code/" + slug);
        r.setSession(new org.springframework.mock.web.MockHttpSession());
        return r;
    }

    // ──────────────────────── Le chemin qui fonctionne ────────────────────────

    @Test
    void unAdministrateurConnu_entre_etDevientUnUtilisateurApplicatif() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        configurer(e, "ecole.ci");
        compte(e, "directrice@ecole.ci", ERole.ROLE_ADMIN, true);

        String redirection = jouer(retourDuFournisseur(e.getSlug(), "directrice@ecole.ci", true),
                requete(e.getSlug()));

        assertThat(redirection).isEqualTo("/admin/dashboard");
        // Le point qui compte : au-delà du gestionnaire, plus rien dans l'application ne doit
        // savoir que le SSO existe. L'isolation entre établissements, l'établissement courant et
        // les verrous d'offre lisent tous un User ; une identité OIDC les traverserait sans
        // qu'aucun ne sache quoi en faire.
        var principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal).isInstanceOf(User.class);
        assertThat(((User) principal).getEmail()).isEqualTo("directrice@ecole.ci");
        assertThat(((User) principal).getEtablissement().getId()).isEqualTo(e.getId());
    }

    @Test
    void laCasseDeLAdresse_neDecideDeRien() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        configurer(e, null);
        compte(e, "directrice@ecole.ci", ERole.ROLE_ADMIN, true);

        // Un fournisseur qui renvoie « Directrice@Ecole.CI » désigne la même personne. Lui
        // refuser l'entrée pour une majuscule serait incompréhensible du côté de l'utilisateur.
        assertThat(jouer(retourDuFournisseur(e.getSlug(), "Directrice@Ecole.CI", true),
                requete(e.getSlug())))
                .isEqualTo("/admin/dashboard");
    }

    /**
     * Ce que ce test prouve, et ce qu'il ne prouve pas.
     *
     * <p>Le profil de test coupe le second facteur ({@code admin-otp.enabled=false}) : l'arbitrage
     * « le SSO remplace-t-il notre code ? » est donc ici tranché d'avance, et ce test ne le
     * vérifie pas. Il vérifie autre chose, qui compte aussi : que l'interrupteur d'exploitation
     * étant coupé, le drapeau de session est bien posé — sans lui, le garde de {@code /admin/**}
     * renverrait vers une page de code qu'aucun courriel n'a préparée, et personne n'entrerait.
     *
     * <p>L'arbitrage lui-même est tenu par {@code SsoWebTest}, qui rallume l'interrupteur.
     */
    @Test
    void lInterrupteurDExploitationCoupe_laisseLaSessionOuverte() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        configurer(e, null);
        compte(e, "chef@ecole.ci", ERole.ROLE_ADMIN, true);

        MockHttpServletRequest requete = requete(e.getSlug());
        jouer(retourDuFournisseur(e.getSlug(), "chef@ecole.ci", true), requete);

        // Sans ce drapeau, l'intercepteur qui garde /admin/** renverrait vers une page de code
        // qu'aucun courriel n'a préparée : personne n'entrerait, et la cause serait invisible.
        assertThat(requete.getSession().getAttribute(AdminOtpController.VERIFIED))
                .isEqualTo(true);
    }

    @Test
    void laDerniereConnexion_estHorodatee() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        ConfigurationSso config = configurer(e, null);
        compte(e, "chef@ecole.ci", ERole.ROLE_ADMIN, true);
        assertThat(config.getDerniereConnexion()).isNull();

        jouer(retourDuFournisseur(e.getSlug(), "chef@ecole.ci", true), requete(e.getSlug()));

        // Une configuration qui n'a jamais servi se repère : c'est souvent le signe d'une
        // configuration fausse que personne n'a signalée.
        assertThat(configurationSsoRepository.findByEtablissementId(e.getId()))
                .get().extracting(ConfigurationSso::getDerniereConnexion).isNotNull();
    }

    // ──────────────────────────── Les refus ────────────────────────────

    @Test
    void uneAdresseInconnue_neCreeAucunCompte() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        configurer(e, null);

        String redirection = jouer(retourDuFournisseur(e.getSlug(), "inconnue@ecole.ci", true),
                requete(e.getSlug()));

        // LA règle du dispositif. Sans elle, un fournisseur mal configuré — ou un domaine
        // racheté — ferait naître des administrateurs d'un établissement.
        assertThat(redirection).isEqualTo("/admin-login?sso_error=inconnu-compte");
        assertThat(userRepository.findAllByEmailIgnoreCase("inconnue@ecole.ci")).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void leCompteDUnAutreEtablissement_nOuvrePas() throws Exception {
        Etablissement cible = etablissement(Plan.ENTERPRISE);
        configurer(cible, null);
        Etablissement voisine = etablissement(Plan.ENTERPRISE);
        compte(voisine, "espion@voisine.ci", ERole.ROLE_ADMIN, true);

        // Le cœur du cloisonnement : un établissement qui maîtrise SON fournisseur d'identité
        // peut lui faire affirmer n'importe quelle adresse — y compris celle d'un administrateur
        // d'une autre école. C'est le rattachement du compte, et lui seul, qui l'arrête.
        String redirection = jouer(
                retourDuFournisseur(cible.getSlug(), "espion@voisine.ci", true),
                requete(cible.getSlug()));

        assertThat(redirection).isEqualTo("/admin-login?sso_error=inconnu-compte");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void uneAdresseNonVerifiee_estRefusee() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        configurer(e, null);
        compte(e, "directrice@ecole.ci", ERole.ROLE_ADMIN, true);

        // Certains fournisseurs laissent déclarer une adresse quelconque sans jamais la
        // confirmer. Or c'est l'adresse, ici, qui désigne le compte : « authentifié » sans
        // « adresse vérifiée » ne veut alors rien dire.
        assertThat(jouer(retourDuFournisseur(e.getSlug(), "directrice@ecole.ci", false),
                requete(e.getSlug())))
                .isEqualTo("/admin-login?sso_error=non-verifie");
    }

    @Test
    void unDomaineHorsListe_estRefuse() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        configurer(e, "ecole.ci");
        compte(e, "directrice@gmail.com", ERole.ROLE_ADMIN, true);

        assertThat(jouer(retourDuFournisseur(e.getSlug(), "directrice@gmail.com", true),
                requete(e.getSlug())))
                .isEqualTo("/admin-login?sso_error=domaine");
    }

    @Test
    void unSousDomaineNonListe_neSeGlissePasDansLaListe() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        configurer(e, "ecole.ci");
        compte(e, "pirate@faux-ecole.ci", ERole.ROLE_ADMIN, true);

        // « faux-ecole.ci » contient « ecole.ci » : une comparaison par inclusion l'accepterait.
        // La comparaison est exacte, domaine par domaine.
        assertThat(jouer(retourDuFournisseur(e.getSlug(), "pirate@faux-ecole.ci", true),
                requete(e.getSlug())))
                .isEqualTo("/admin-login?sso_error=domaine");
    }

    @Test
    void unCompteDesactive_nEntrePlus() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        configurer(e, null);
        compte(e, "parti@ecole.ci", ERole.ROLE_ADMIN, false);

        assertThat(jouer(retourDuFournisseur(e.getSlug(), "parti@ecole.ci", true),
                requete(e.getSlug())))
                .isEqualTo("/admin-login?sso_error=desactive");
    }

    @Test
    void unCompteSansRoleAdministrateur_nEntrePas() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        configurer(e, null);
        compte(e, "enseignant@ecole.ci", ERole.ROLE_ENSEIGNANT, true);

        // Les enseignants émargent depuis l'application mobile ; l'espace d'administration ne
        // leur est pas destiné, et le SSO n'est pas une façon détournée d'y entrer.
        assertThat(jouer(retourDuFournisseur(e.getSlug(), "enseignant@ecole.ci", true),
                requete(e.getSlug())))
                .isEqualTo("/admin-login?sso_error=role");
    }

    @Test
    void leSuperAdministrateur_neSeConnectePasParLAnnuaireDUnClient() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        configurer(e, null);
        compte(e, "plateforme@sigep.store", ERole.ROLE_SUPER_ADMIN, true);

        // Frontière volontaire : le compte qui gouverne TOUS les établissements ne doit pas
        // pouvoir être affirmé par le fournisseur d'identité de l'un d'eux. Ce serait confier
        // les clés de la plateforme à l'annuaire d'un client.
        assertThat(jouer(retourDuFournisseur(e.getSlug(), "plateforme@sigep.store", true),
                requete(e.getSlug())))
                .isEqualTo("/admin-login?sso_error=super-admin");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void unPlanSansLOption_neLaissePasEntrer_memeConfigure() throws Exception {
        Etablissement e = etablissement(Plan.PRO);
        configurer(e, null);
        compte(e, "directrice@ecole.ci", ERole.ROLE_ADMIN, true);

        // Un établissement qui redescend d'offre garde sa configuration en base. Si le verrou
        // ne vivait que sur l'écran de configuration, la connexion continuerait de fonctionner
        // indéfiniment — c'est exactement ce qui s'était produit avec l'export paie.
        assertThat(jouer(retourDuFournisseur(e.getSlug(), "directrice@ecole.ci", true),
                requete(e.getSlug())))
                .isEqualTo("/admin-login?sso_error=plan");
    }

    @Test
    void unAbonnementExpire_fermeAussiLaPorteDuSso() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        e.setDateExpiration(LocalDate.now().minusDays(1));
        etablissementRepository.save(e);
        configurer(e, null);
        compte(e, "directrice@ecole.ci", ERole.ROLE_ADMIN, true);

        // Une seconde porte d'entrée ne doit pas être une porte plus facile : le SSO ne
        // contourne aucune des conditions qui ferment déjà la connexion par mot de passe.
        assertThat(jouer(retourDuFournisseur(e.getSlug(), "directrice@ecole.ci", true),
                requete(e.getSlug())))
                .isEqualTo("/admin-login?sso_error=suspendu");
    }

    @Test
    void unEtablissementNonValide_nEntrePasNonPlus() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        e.setStatut(StatutEtablissement.EN_ATTENTE);
        etablissementRepository.save(e);
        configurer(e, null);
        compte(e, "directrice@ecole.ci", ERole.ROLE_ADMIN, true);

        assertThat(jouer(retourDuFournisseur(e.getSlug(), "directrice@ecole.ci", true),
                requete(e.getSlug())))
                .isEqualTo("/admin-login?sso_error=statut");
    }

    @Test
    void uneConfigurationDesactivee_fermeLaPorte() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        ConfigurationSso config = configurer(e, null);
        config.setActif(false);
        configurationSsoRepository.save(config);
        compte(e, "directrice@ecole.ci", ERole.ROLE_ADMIN, true);

        // L'interrupteur doit couper POUR DE BON, et pas seulement masquer le bouton : c'est
        // ce qu'on actionne le jour où l'on soupçonne que le fournisseur est compromis.
        assertThat(jouer(retourDuFournisseur(e.getSlug(), "directrice@ecole.ci", true),
                requete(e.getSlug())))
                .isEqualTo("/admin-login?sso_error=inactif");
    }

    @Test
    void unEtablissementSansConfiguration_neLaisseRienPasser() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        compte(e, "directrice@ecole.ci", ERole.ROLE_ADMIN, true);

        assertThat(jouer(retourDuFournisseur(e.getSlug(), "directrice@ecole.ci", true),
                requete(e.getSlug())))
                .isEqualTo("/admin-login?sso_error=config");
    }

    @Test
    void unSlugInconnu_neMeneNullePart() throws Exception {
        assertThat(jouer(retourDuFournisseur("etablissement-fantome", "x@y.ci", true),
                requete("etablissement-fantome")))
                .isEqualTo("/admin-login?sso_error=etablissement");
    }

    @Test
    void unHomonymeCreeChezUnConcurrent_neFermePasLaPorte() throws Exception {
        Etablissement cible = etablissement(Plan.ENTERPRISE);
        configurer(cible, null);
        compte(cible, "directrice@ecole-cible.ci", ERole.ROLE_ADMIN, true);

        // Un établissement voisin crée chez LUI un compte dont l'adresse ne diffère que par la
        // casse. La contrainte d'unicité de la colonne ne s'y oppose pas : « Directrice@… » et
        // « directrice@… » sont deux lignes distinctes.
        Etablissement voisine = etablissement(Plan.ENTERPRISE);
        compte(voisine, "Directrice@Ecole-Cible.ci", ERole.ROLE_ADMIN, true);

        // Si la recherche de compte balayait toute la base, elle trouverait DEUX homonymes et
        // refuserait pour ambiguïté : n'importe quel client fermerait ainsi la porte SSO d'un
        // concurrent, sans jamais toucher à ses données.
        assertThat(jouer(retourDuFournisseur(cible.getSlug(), "directrice@ecole-cible.ci", true),
                requete(cible.getSlug())))
                .isEqualTo("/admin/dashboard");
    }

    @Test
    void unRefus_neJettePasDehorsQuelquUnQuiTravaillait() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        configurer(e, null);
        User enPlaceDansLOnglet = compte(e, "occupee@ecole.ci", ERole.ROLE_ADMIN, true);

        // Un administrateur déjà connecté par mot de passe. Il ouvre dans un second onglet le
        // lien SSO qu'un collègue lui a transmis, et ce lien échoue.
        MockHttpServletRequest requete = requete(e.getSlug());
        var session = (org.springframework.mock.web.MockHttpSession) requete.getSession();
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(enPlaceDansLOnglet, "n/a",
                        enPlaceDansLOnglet.getAuthorities()));
        session.setAttribute(org.springframework.security.web.context
                .HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, contexte);

        jouer(retourDuFournisseur(e.getSlug(), "inconnue@ecole.ci", true), requete);

        // Le détruire le jetterait dehors au milieu de son travail, pour un échec qui ne le
        // concerne pas — et la panne d'un fournisseur deviendrait une déconnexion générale.
        assertThat(session.isInvalid())
                .as("la session d'un administrateur deja connecte ne doit pas etre detruite")
                .isFalse();
        assertThat(session.getAttribute(org.springframework.security.web.context
                .HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNotNull();
    }

    @Test
    void laSessionOuverte_porteLIdentiteApplicative_etPasSeulementLeContexteDuFil() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        configurer(e, null);
        compte(e, "chef@ecole.ci", ERole.ROLE_ADMIN, true);

        MockHttpServletRequest requete = requete(e.getSlug());
        jouer(retourDuFournisseur(e.getSlug(), "chef@ecole.ci", true), requete);

        // Lire le SecurityContextHolder ne prouve presque rien : le gestionnaire vient de l'y
        // poser lui-même, et il serait perdu à la fin de la requête. Ce qui survit à la
        // redirection, c'est ce qui est ÉCRIT EN SESSION — et c'est de là que l'application
        // relira l'identité à la requête suivante.
        Object contexte = requete.getSession().getAttribute(
                org.springframework.security.web.context.HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY);
        assertThat(contexte).isInstanceOf(org.springframework.security.core.context.SecurityContext.class);
        var principal = ((org.springframework.security.core.context.SecurityContext) contexte)
                .getAuthentication().getPrincipal();
        assertThat(principal).isInstanceOf(User.class);
        assertThat(((User) principal).getEmail()).isEqualTo("chef@ecole.ci");
    }

    @Test
    void uneSessionRefusee_estDetruite() throws Exception {
        Etablissement e = etablissement(Plan.ENTERPRISE);
        configurer(e, null);

        MockHttpServletRequest requete = requete(e.getSlug());
        var session = (org.springframework.mock.web.MockHttpSession) requete.getSession();
        jouer(retourDuFournisseur(e.getSlug(), "inconnue@ecole.ci", true), requete);

        // Laisser une session à demi ouverte — authentifiée auprès du fournisseur, sans aucun
        // rôle chez nous — ne sert à rien d'autre qu'à produire un état que personne n'a prévu.
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
