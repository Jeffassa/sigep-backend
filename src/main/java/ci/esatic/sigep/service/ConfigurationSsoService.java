package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.ConfigurationSso;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.repository.ConfigurationSsoRepository;
import ci.esatic.sigep.security.sso.ChiffrementSecret;
import ci.esatic.sigep.security.sso.DecouverteOidc;
import ci.esatic.sigep.security.sso.EmetteurAutorise;
import ci.esatic.sigep.security.sso.RegistreSsoTenant;
import ci.esatic.sigep.security.sso.SsoIndisponibleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * Enregistre et vérifie le fournisseur d'identité d'un établissement.
 *
 * <p>Tout ce qu'un administrateur fait depuis l'écran {@code /admin/sso} passe par ici. Deux
 * partis pris s'y lisent.
 *
 * <p><b>Le secret ne ressort jamais.</b> Il est chiffré en entrant et n'est plus jamais rendu —
 * ni à l'écran, ni dans un journal, ni dans un message d'erreur. L'administrateur qui revient sur
 * sa configuration voit une case vide : la laisser vide conserve le secret enregistré, la
 * remplir le remplace. C'est la seule façon de pouvoir modifier un domaine autorisé sans avoir à
 * retrouver un secret qu'on n'a plus.
 *
 * <p><b>On vérifie avant d'enregistrer.</b> Une configuration fausse ne se découvrirait sinon
 * qu'à la première tentative de connexion — c'est-à-dire par un administrateur enfermé dehors,
 * avec pour seule indication « la connexion n'a pas pu aboutir ». La découverte OIDC est donc
 * tentée tout de suite : si le fournisseur ne répond pas, rien n'est enregistré et la raison
 * s'affiche.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfigurationSsoService {

    private final ConfigurationSsoRepository repository;
    private final ChiffrementSecret chiffrement;
    private final DecouverteOidc decouverte;
    private final RegistreSsoTenant registre;

    /** Le SSO est-il seulement installable sur ce serveur ? Sans clé, non. */
    public boolean disponibleSurCeServeur() {
        return chiffrement.disponible();
    }

    public Optional<ConfigurationSso> charger(Etablissement etablissement) {
        if (etablissement == null) return Optional.empty();
        return repository.findByEtablissementId(etablissement.getId());
    }

    /**
     * Le secret enregistré est-il encore lisible ?
     *
     * <p>Une clé de chiffrement changée ou perdue ne casse rien de visible : l'écran continue
     * d'afficher une configuration complète et saine, le fournisseur est bien déclaré, la case
     * « Activer » est cochée — et <b>chaque connexion échoue</b>, sans que rien ne relie les deux.
     * L'administrateur conclut à une panne de son annuaire et cherche du mauvais côté.
     *
     * <p>Un déchiffrement à blanc répond à la question tout de suite. Il ne rend jamais le
     * secret : seulement s'il a pu être relu.
     */
    public boolean secretRelisible(Etablissement etablissement) {
        return charger(etablissement).map(c -> {
            try {
                chiffrement.dechiffrer(c.getClientSecret());
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }).orElse(true);   // pas de configuration : rien d'anormal à signaler
    }

    /**
     * Crée ou remplace la configuration de cet établissement.
     *
     * @param secretEnClair nouveau secret, ou vide pour conserver celui déjà enregistré
     * @throws SsoIndisponibleException si la configuration ne tient pas debout
     */
    @Transactional
    public ConfigurationSso enregistrer(Etablissement etablissement, String fournisseur,
                                        String issuer, String clientId, String secretEnClair,
                                        String domaines, boolean actif, boolean exigerOtp) {
        if (etablissement == null) {
            throw new SsoIndisponibleException("Aucun établissement courant.");
        }
        if (!chiffrement.disponible()) {
            throw new SsoIndisponibleException(
                    "Le SSO n'est pas activé sur ce serveur : la clé de chiffrement "
                    + "APP_SSO_SECRET_KEY n'est pas installée. Contactez-nous.");
        }

        String issuerNet = issuer == null ? "" : issuer.trim();
        // Une barre oblique finale produit « https://x.ci//.well-known/… » chez certains
        // fournisseurs, qui répondent alors 404. L'erreur est invisible à la lecture.
        while (issuerNet.endsWith("/")) {
            issuerNet = issuerNet.substring(0, issuerNet.length() - 1);
        }
        EmetteurAutorise.verifier(issuerNet);

        String idClient = exigerNonVide(clientId, "L'identifiant client est obligatoire.");
        String nomFournisseur = exigerNonVide(fournisseur, "Le nom du fournisseur est obligatoire.");

        ConfigurationSso config = repository.findByEtablissementId(etablissement.getId())
                .orElseGet(() -> ConfigurationSso.builder()
                        .etablissementId(etablissement.getId())
                        .creeLe(LocalDateTime.now())
                        .build());

        String secret = secretEnClair == null ? "" : secretEnClair.trim();
        if (!secret.isEmpty()) {
            config.setClientSecret(chiffrement.chiffrer(secret));
        } else if (config.getClientSecret() == null) {
            throw new SsoIndisponibleException("Le secret client est obligatoire.");
        }

        config.setFournisseur(nomFournisseur);
        config.setIssuer(issuerNet);
        config.setClientId(idClient);
        config.setDomaines(normaliserDomaines(domaines));
        config.setActif(actif);
        config.setExigerOtp(exigerOtp);
        config.setModifieLe(LocalDateTime.now());

        // Avant d'écrire : le fournisseur répond-il seulement ? Une configuration fausse
        // enregistrée est un administrateur enfermé dehors, qui n'apprendra la cause de rien.
        //
        // SAUF quand on DÉSACTIVE. Exiger que le fournisseur réponde pour avoir le droit de
        // couper le SSO rendait l'interrupteur inutilisable au seul moment où l'on en a besoin :
        // celui où le fournisseur est en panne. L'administrateur se retrouvait devant une case à
        // décocher et un enregistrement qui refusait de passer.
        if (actif) {
            verifierAupresDuFournisseur(issuerNet);
        }

        ConfigurationSso enregistree = repository.save(config);
        // Le registre garde une heure en mémoire ce qu'il a découvert : sans cet oubli, une
        // correction ne prendrait effet qu'au bout d'une heure, et l'administrateur croirait
        // que sa correction n'a servi à rien.
        registre.oublier(etablissement.getSlug());
        log.info("SSO : configuration enregistrée pour « {} » auprès de {}",
                etablissement.getSlug(), issuerNet);
        return enregistree;
    }

    /**
     * Supprime la configuration. Le SSO cesse, les mots de passe restent.
     *
     * <p>Rien d'autre à défaire : aucun compte n'ayant été créé par le SSO, aucun ne disparaît
     * avec lui. C'est l'une des raisons pour lesquelles les comptes ne sont pas créés à la volée.
     */
    @Transactional
    public boolean supprimer(Etablissement etablissement) {
        if (etablissement == null) return false;
        Optional<ConfigurationSso> config = repository.findByEtablissementId(etablissement.getId());
        config.ifPresent(c -> {
            repository.delete(c);
            registre.oublier(etablissement.getSlug());
        });
        return config.isPresent();
    }

    private void verifierAupresDuFournisseur(String issuer) {
        try {
            decouverte.decouvrir(issuer);
        } catch (RuntimeException e) {
            throw new SsoIndisponibleException(
                    "Aucun fournisseur OpenID Connect ne répond à cette adresse. Vérifiez "
                    + "qu'elle correspond bien à l'« issuer » indiqué par votre fournisseur "
                    + "(celui dont l'adresse + /.well-known/openid-configuration renvoie une "
                    + "configuration).");
        }
    }

    private static String exigerNonVide(String valeur, String message) {
        String v = valeur == null ? "" : valeur.trim();
        if (v.isEmpty()) throw new SsoIndisponibleException(message);
        return v;
    }

    /**
     * Met les domaines en forme : minuscules, sans espace, sans doublon, sans arobase.
     *
     * <p>La saisie « @esatic.ci, ESATIC.CI » est naturelle et donnerait, telle quelle, deux
     * entrées dont aucune ne correspondrait jamais à un domaine de courriel.
     */
    private static String normaliserDomaines(String brut) {
        if (brut == null || brut.isBlank()) return null;
        var propres = new java.util.LinkedHashSet<String>();
        for (String morceau : brut.split("[,;\\s]+")) {
            String d = morceau.trim().toLowerCase(Locale.ROOT);
            if (d.startsWith("@")) d = d.substring(1);
            if (!d.isEmpty()) propres.add(d);
        }
        return propres.isEmpty() ? null : String.join(",", propres);
    }
}
