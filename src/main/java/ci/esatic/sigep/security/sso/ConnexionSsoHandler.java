package ci.esatic.sigep.security.sso;

import ci.esatic.sigep.controller.web.AdminOtpController;
import ci.esatic.sigep.entity.ConfigurationSso;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.SeveriteEvenement;
import ci.esatic.sigep.entity.StatutEtablissement;
import ci.esatic.sigep.entity.TypeEvenement;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.ConfigurationSsoRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.security.ClientIpResolver;
import ci.esatic.sigep.service.AbonnementService;
import ci.esatic.sigep.service.JournalSecuriteService;
import ci.esatic.sigep.service.MailService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Ce qui se passe quand le fournisseur d'identité nous renvoie quelqu'un.
 *
 * <p>C'est la pièce la plus délicate du SSO, et la raison est simple : à cet instant, une machine
 * que nous ne possédons pas vient d'affirmer une identité, et il faut décider si cette affirmation
 * ouvre une porte. Tout le reste du dispositif n'est que de la plomberie autour de cette décision.
 *
 * <h2>Le principe : le fournisseur authentifie, il n'autorise pas</h2>
 *
 * <p>Son rôle s'arrête à « cette personne est bien titulaire de cette adresse ». Qui a le droit
 * d'entrer, et avec quels pouvoirs, reste décidé chez nous, à partir d'un compte qui <b>existe
 * déjà</b>. Aucun compte n'est créé à la volée : sans cette règle, un fournisseur mal configuré —
 * ou un domaine racheté — ferait naître des administrateurs. Un nouvel administrateur se crée ici,
 * puis se connecte par SSO ; jamais l'inverse.
 *
 * <h2>Ce qui est vérifié, dans l'ordre</h2>
 *
 * <ol>
 *   <li>L'établissement désigné par l'URL de retour existe, est validé, n'est pas suspendu, et son
 *       plan comprend le SSO.</li>
 *   <li>Le fournisseur affirme que l'adresse est <b>vérifiée</b> ({@code email_verified}). Sans
 *       cela, « authentifié » ne veut rien dire : certains fournisseurs laissent déclarer une
 *       adresse quelconque.</li>
 *   <li>Le domaine de l'adresse figure parmi ceux que l'établissement accepte, s'il en a listé.</li>
 *   <li>Un compte porte cette adresse, un seul, il est actif, et il appartient à
 *       <b>cet</b> établissement.</li>
 *   <li>Ce compte est administrateur d'établissement — et pas super-administrateur de la
 *       plateforme : le compte qui gouverne tous les établissements ne doit jamais pouvoir être
 *       affirmé par le fournisseur d'identité d'un client.</li>
 * </ol>
 *
 * <p>Un seul de ces points qui manque, et la session est détruite plutôt que laissée à moitié
 * ouverte : sans cela le visiteur resterait authentifié en tant qu'« utilisateur OIDC » sans
 * aucun rôle — inoffensif, mais c'est le genre d'état intermédiaire dont on se mord les doigts.
 *
 * <h2>Pourquoi l'identité est remplacée</h2>
 *
 * <p>Tout le reste de l'application — l'isolation entre établissements, l'établissement courant,
 * le second facteur — lit un {@code User} dans le contexte de sécurité. Plutôt que d'apprendre à
 * chacun de ces endroits qu'il existe désormais une seconde forme d'identité, le jeton OIDC est
 * échangé ici contre exactement celui que produit la connexion par mot de passe. Le SSO
 * s'arrête donc à cette ligne : au-delà, plus rien dans l'application ne sait qu'il existe.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConnexionSsoHandler implements AuthenticationSuccessHandler {

    private final EtablissementRepository etablissementRepository;
    private final ConfigurationSsoRepository configurationSsoRepository;
    private final UserRepository userRepository;
    private final PlanService planService;
    private final AbonnementService abonnementService;
    private final JournalSecuriteService journalSecurite;
    private final ClientIpResolver resolveurIp;
    private final MailService mailService;

    private final SecurityContextRepository depotContexte = new HttpSessionSecurityContextRepository();

    /** Le même interrupteur d'exploitation que la connexion par mot de passe. */
    @org.springframework.beans.factory.annotation.Value("${app.security.admin-otp.enabled:true}")
    private boolean otpActif;

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest requete, HttpServletResponse reponse,
                                        Authentication authentication) throws IOException {
        try {
            accueillir(requete, reponse, authentication);
        } catch (RefusSso refus) {
            refuser(requete, reponse, refus.getMessage(), refus.motif, refus.identifiant);
        } catch (RuntimeException e) {
            // Le détail technique reste dans les journaux du serveur : ce qui s'affiche sur
            // l'écran de connexion ne doit rien apprendre sur notre installation.
            log.error("SSO : échec inattendu au retour du fournisseur d'identité", e);
            refuser(requete, reponse, "La connexion n'a pas pu aboutir.", "erreur", null);
        }
    }

    private void accueillir(HttpServletRequest requete, HttpServletResponse reponse,
                            Authentication authentication) throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken jeton)
                || !(jeton.getPrincipal() instanceof OidcUser oidc)) {
            throw new RefusSso("La connexion n'a pas pu aboutir.", "jeton", null);
        }

        String slug = jeton.getAuthorizedClientRegistrationId();
        Etablissement etablissement = etablissementRepository.findBySlug(slug)
                .orElseThrow(() -> new RefusSso("Établissement inconnu.", "etablissement", null));

        // Revérifié ici bien que le registre l'ait déjà fait : entre la redirection vers le
        // fournisseur et le retour, il s'écoule le temps d'une saisie de mot de passe et d'un
        // second facteur. Un abonnement peut y expirer, un établissement y être suspendu.
        if (etablissement.getStatut() != null
                && etablissement.getStatut() != StatutEtablissement.VALIDE) {
            throw new RefusSso("Votre espace n'est pas encore activé.", "statut", null);
        }
        if (abonnementService.accesSuspendu(etablissement)) {
            throw new RefusSso("L'accès à votre espace est suspendu.", "suspendu", null);
        }
        if (!planService.estDisponible(etablissement, Feature.SSO)) {
            throw new RefusSso("Le SSO ne fait pas partie de votre offre.", "plan", null);
        }

        ConfigurationSso config = configurationSsoRepository
                .findByEtablissementId(etablissement.getId())
                .orElseThrow(() -> new RefusSso("Le SSO n'est pas configuré.", "config", null));
        if (!config.isActif()) {
            throw new RefusSso("Le SSO est désactivé pour votre établissement.", "inactif", null);
        }

        String email = normaliser(oidc.getEmail());
        if (email == null) {
            throw new RefusSso(
                    "Votre fournisseur d'identité n'a pas transmis d'adresse e-mail.", "email", null);
        }

        // « Authentifié » sans « adresse vérifiée » ne veut rien dire : certains fournisseurs
        // laissent déclarer une adresse quelconque sans jamais la confirmer, et c'est
        // précisément l'adresse qui, ici, désigne le compte.
        if (!Boolean.TRUE.equals(oidc.getEmailVerified())) {
            throw new RefusSso("Votre adresse n'est pas vérifiée chez votre fournisseur d'identité.",
                    "non-verifie", email);
        }

        if (!config.accepteLeDomaine(email)) {
            throw new RefusSso("Cette adresse n'appartient pas à un domaine autorisé.",
                    "domaine", email);
        }

        // Volontairement insensible à la casse : « Admin@ecole.ci » chez le fournisseur et
        // « admin@ecole.ci » chez nous désignent la même personne. Mais si DEUX comptes se
        // révélaient ainsi homonymes, on refuse au lieu d'en choisir un : deviner lequel
        // reviendrait à ouvrir une session au hasard entre deux identités.
        List<User> candidats = userRepository.findAllByEmailIgnoreCase(email);
        if (candidats.size() > 1) {
            log.error("SSO : {} comptes portent l'adresse {} — connexion refusée.",
                    candidats.size(), email);
            throw new RefusSso("Plusieurs comptes portent cette adresse. Contactez-nous.",
                    "ambigu", email);
        }
        User utilisateur = candidats.isEmpty() ? null : candidats.get(0);

        if (utilisateur == null || utilisateur.getEtablissement() == null
                || !etablissement.getId().equals(utilisateur.getEtablissement().getId())) {
            // Un seul message pour les deux cas — compte inexistant, ou compte d'un AUTRE
            // établissement. Les distinguer apprendrait à un fournisseur d'identité quelles
            // adresses existent ailleurs chez nous.
            throw new RefusSso("Aucun compte de cet établissement ne porte cette adresse.",
                    "inconnu-compte", email);
        }
        if (!utilisateur.isEnabled()) {
            throw new RefusSso("Ce compte est désactivé.", "desactive", email);
        }

        boolean superAdmin = aLeRole(utilisateur, "ROLE_SUPER_ADMIN");
        if (superAdmin) {
            // Frontière volontaire. Le compte qui gouverne TOUS les établissements ne doit pas
            // pouvoir être affirmé par le fournisseur d'identité de l'un d'eux : ce serait
            // confier les clés de la plateforme à l'annuaire d'un client.
            log.warn("SSO : connexion super-administrateur refusée pour {} via {}.",
                    email, slug);
            throw new RefusSso("Le compte plateforme ne se connecte pas par SSO.",
                    "super-admin", email);
        }
        if (!aLeRole(utilisateur, "ROLE_ADMIN")) {
            throw new RefusSso("Ce compte n'a pas accès à l'administration.", "role", email);
        }

        ouvrirLaSession(requete, reponse, utilisateur, config, email, slug);
    }

    /**
     * Échange le jeton OIDC contre l'identité applicative, et mène au bon endroit.
     *
     * <p>Le contexte est écrit explicitement dans la session : le filtre OAuth2 y a déjà rangé
     * le jeton OIDC avant de nous appeler, et sans cette écriture c'est lui qui survivrait à la
     * redirection — un principal que l'isolation entre établissements ne sait pas lire.
     */
    private void ouvrirLaSession(HttpServletRequest requete, HttpServletResponse reponse,
                                 User utilisateur, ConfigurationSso config,
                                 String email, String slug) throws IOException {
        SecurityContext contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(new UsernamePasswordAuthenticationToken(
                utilisateur, "n/a", utilisateur.getAuthorities()));
        SecurityContextHolder.setContext(contexte);
        depotContexte.saveContext(contexte, requete, reponse);

        config.setDerniereConnexion(LocalDateTime.now());
        configurationSsoRepository.save(config);

        HttpSession session = requete.getSession(true);
        boolean secondFacteur = otpActif && config.isExigerOtp();

        journalSecurite.enregistrer(TypeEvenement.CONNEXION_SSO_REUSSIE, SeveriteEvenement.INFO,
                resolveurIp.resolve(requete), email, "/login/oauth2/code/" + slug,
                "Connexion SSO via " + config.getFournisseur()
                        + (secondFacteur ? " ; code a six chiffres exige en plus" : ""),
                utilisateur.getEtablissement() == null ? null
                        : utilisateur.getEtablissement().getId());

        if (secondFacteur) {
            AdminOtpController.preparer(session, utilisateur.getEmail(), mailService, false);
            reponse.sendRedirect("/admin-otp");
            return;
        }

        // Sans second facteur supplémentaire, la session est ouverte pour de bon : il faut le
        // dire à l'intercepteur qui garde /admin/**, sinon il renverrait vers une page de code
        // qu'aucun courriel n'a jamais préparée — et personne n'entrerait.
        session.setAttribute(AdminOtpController.VERIFIED, true);
        reponse.sendRedirect("/admin/dashboard");
    }

    /**
     * Ferme tout et renvoie à la page de connexion avec une explication.
     *
     * <p>La session est détruite, pas seulement vidée : le visiteur est arrivé ici authentifié
     * auprès du fournisseur, et laisser traîner une session à demi ouverte ne sert à rien
     * d'autre qu'à produire un état que personne n'a prévu.
     */
    private void refuser(HttpServletRequest requete, HttpServletResponse reponse,
                         String message, String motif, String identifiant) throws IOException {
        journalSecurite.enregistrer(TypeEvenement.CONNEXION_SSO_REFUSEE, SeveriteEvenement.ALERTE,
                resolveurIp.resolve(requete), identifiant, requete.getRequestURI(),
                "SSO refuse (" + motif + ") : " + message);

        SecurityContextHolder.clearContext();
        HttpSession session = requete.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        // C'est le MOTIF qui voyage, pas la phrase. Une adresse comme
        // « /admin-login?sso_error=Votre+compte+est+pirate,+appelez+le... » serait un hameçonnage
        // tout fait : le message s'afficherait sur notre page, dans notre charte, et paraitrait
        // venir de nous. Le gabarit ne sait traduire qu'une liste fermee de motifs ; tout le
        // reste retombe sur une phrase generique.
        reponse.sendRedirect("/admin-login?sso_error="
                + java.net.URLEncoder.encode(motif, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static boolean aLeRole(User u, String role) {
        return u.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }

    private static String normaliser(String email) {
        if (email == null) return null;
        String v = email.trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    /** Refus explicite, porteur du message affiché et du motif journalisé. */
    private static class RefusSso extends RuntimeException {
        private final String motif;
        private final String identifiant;

        RefusSso(String message, String motif, String identifiant) {
            super(message);
            this.motif = motif;
            this.identifiant = identifiant;
        }
    }
}
