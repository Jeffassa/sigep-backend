package ci.esatic.sigep.security.sso;

import ci.esatic.sigep.entity.ConfigurationSso;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.StatutEtablissement;
import ci.esatic.sigep.repository.ConfigurationSsoRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.service.AbonnementService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Où Spring Security va chercher « le fournisseur d'identité numéro X ».
 *
 * <p>Spring attend normalement une liste d'inscriptions figée dans la configuration du serveur.
 * Ici il y en a autant que d'établissements, elles vivent en base, et elles changent sans
 * redéploiement : d'où ce registre, qui les fabrique à la demande.
 *
 * <p><b>L'identifiant d'inscription est le slug de l'établissement.</b> Ce choix n'est pas
 * cosmétique : il rend l'URL de retour ({@code /login/oauth2/code/<slug>}) stable et propre à un
 * seul établissement, si bien qu'un code d'autorisation émis pour l'un ne peut pas être présenté
 * sur l'adresse de l'autre.
 *
 * <h2>Ce que ce registre refuse, et pourquoi ici</h2>
 *
 * <p>Plan sans l'option SSO, configuration désactivée, établissement non validé ou suspendu : tout
 * cela est refusé <b>avant même</b> d'envoyer le visiteur chez son fournisseur d'identité. Le
 * placer ici plutôt que sur l'écran de configuration est la leçon de l'export paie : un verrou
 * qui ne vit que dans l'interface ne verrouille rien, puisque l'adresse se devine. Un
 * établissement qui cesse de payer cesse donc d'entrer, le jour même, sans que personne n'ait à
 * désactiver quoi que ce soit à la main.
 *
 * <h2>Le cache</h2>
 *
 * <p>La découverte OIDC est un appel réseau. Sans cache, chaque clic sur « se connecter » en
 * déclencherait deux — un pour la redirection, un pour le retour — et une panne du fournisseur
 * nous ferait attendre à chaque fois. Les inscriptions sont donc gardées une heure, et
 * immédiatement remplacées dès que la configuration change : l'empreinte conservée avec chaque
 * entrée le détecte sans qu'on ait à penser à vider quoi que ce soit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RegistreSsoTenant implements ClientRegistrationRepository {

    /** Au-delà, on redemande au fournisseur où sont ses points d'accès. */
    private static final Duration FRAICHEUR = Duration.ofHours(1);

    private final EtablissementRepository etablissementRepository;
    private final ConfigurationSsoRepository configurationSsoRepository;
    private final ChiffrementSecret chiffrement;
    private final PlanService planService;
    private final AbonnementService abonnementService;
    private final DecouverteOidc decouverte;

    private record Entree(ClientRegistration inscription, String empreinte, Instant expire) {}

    private final Map<String, Entree> cache = new ConcurrentHashMap<>();

    /**
     * Inscription utilisable pour cet établissement, ou {@code null}.
     *
     * <p>{@code null} et non une exception : c'est le contrat de Spring Security, qui en tire un
     * refus propre. Et c'est aussi la bonne réponse vis-à-vis du visiteur — « ce slug n'ouvre
     * rien » ne dit pas s'il désigne un établissement sans SSO, un plan insuffisant ou rien du
     * tout.
     */
    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (registrationId == null || registrationId.isBlank()) return null;

        Etablissement etablissement = etablissementRepository.findBySlug(registrationId).orElse(null);
        if (etablissement == null) {
            cache.remove(registrationId);
            return null;
        }
        ConfigurationSso config = configurationSsoRepository
                .findByEtablissementId(etablissement.getId()).orElse(null);
        if (config == null || !config.isActif()) {
            cache.remove(registrationId);
            return null;
        }
        if (!ouvertALaConnexion(etablissement)) {
            cache.remove(registrationId);
            return null;
        }

        String empreinte = empreinte(config);
        Entree entree = cache.get(registrationId);
        if (entree != null && entree.empreinte().equals(empreinte)
                && Instant.now().isBefore(entree.expire())) {
            return entree.inscription();
        }

        try {
            ClientRegistration inscription = fabriquer(registrationId, config);
            cache.put(registrationId, new Entree(inscription, empreinte,
                    Instant.now().plus(FRAICHEUR)));
            return inscription;
        } catch (SsoIndisponibleException e) {
            log.warn("SSO : configuration inutilisable pour « {} » — {}", registrationId, e.getMessage());
            cache.remove(registrationId);
            return null;
        } catch (RuntimeException e) {
            // Fournisseur injoignable, découverte invalide, délai dépassé. On ne garde rien :
            // la panne est peut-être passagère, et une entrée vide empoisonnerait l'heure
            // suivante. Le visiteur reçoit un refus, pas une page blanche.
            log.warn("SSO : découverte impossible pour « {} » auprès de {} ({})",
                    registrationId, config.getIssuer(), e.getClass().getSimpleName());
            cache.remove(registrationId);
            return null;
        }
    }

    /**
     * L'établissement est-il, en lui-même, en état d'ouvrir une session ?
     *
     * <p>Les mêmes conditions que la connexion par mot de passe, pas d'autres : un dossier non
     * validé, un abonnement expiré ou un compte suspendu ferment la porte quelle que soit la
     * manière d'y frapper. Une seconde porte d'entrée ne doit pas être une porte plus facile.
     */
    private boolean ouvertALaConnexion(Etablissement e) {
        if (e.getStatut() != null && e.getStatut() != StatutEtablissement.VALIDE) return false;
        if (abonnementService.accesSuspendu(e)) return false;
        return planService.estDisponible(e, Feature.SSO);
    }

    /** Change dès qu'un champ qui compte change : c'est ce qui périme l'entrée en cache. */
    private static String empreinte(ConfigurationSso c) {
        return String.join("|", Objects.toString(c.getIssuer(), ""),
                Objects.toString(c.getClientId(), ""),
                Objects.toString(c.getClientSecret(), ""));
    }

    private ClientRegistration fabriquer(String registrationId, ConfigurationSso config) {
        // Revérifié à CHAQUE fabrication, et pas seulement à l'enregistrement : une ligne
        // modifiée directement en base, ou un domaine repris par quelqu'un d'autre depuis, ne
        // doit pas valoir autorisation d'aller frapper à une adresse interne.
        EmetteurAutorise.verifier(config.getIssuer());

        String secret = chiffrement.dechiffrer(config.getClientSecret());
        return decouverte.decouvrir(config.getIssuer())
                .registrationId(registrationId)
                .clientId(config.getClientId())
                .clientSecret(secret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                // L'URL de retour porte le slug : un code d'autorisation émis pour un
                // établissement ne peut pas être présenté sur l'adresse d'un autre.
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                // Le strict nécessaire. « openid » ouvre OIDC, « email » porte l'identité sur
                // laquelle tout repose ici ; « profile » ne sert qu'à nommer la personne dans
                // les journaux. Rien de plus : chaque autorisation demandée en trop est une
                // autorisation qu'un jeton volé emporterait avec lui.
                .scope("openid", "email", "profile")
                .clientName(config.getFournisseur())
                .build();
    }

    /** Oublie ce qui est en cache pour un établissement (après modification de sa configuration). */
    public void oublier(String slug) {
        if (slug != null) cache.remove(slug);
    }

    /** Un fournisseur est-il prêt à recevoir ce visiteur ? Utilisé par les écrans de connexion. */
    public Optional<ClientRegistration> pour(String slug) {
        return Optional.ofNullable(findByRegistrationId(slug));
    }
}
