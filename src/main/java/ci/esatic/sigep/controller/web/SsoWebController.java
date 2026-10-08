package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.ConfigurationSso;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.repository.ConfigurationSsoRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.security.sso.RegistreSsoTenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * La porte d'entrée SSO : « quel est votre établissement ? ».
 *
 * <h2>Le problème que cette page résout</h2>
 *
 * <p>Le SSO suppose de savoir à QUEL fournisseur d'identité s'adresser — donc de connaître
 * l'établissement — alors que personne n'est encore connecté. Il faut bien le demander quelque
 * part, et les deux formulations possibles sont offertes : l'adresse professionnelle, que tout le
 * monde connaît, ou l'identifiant court de l'établissement, pour qui a reçu un lien direct.
 *
 * <p>Un établissement peut aussi distribuer {@code /sso/<identifiant>} à ses agents : ce lien
 * mène droit au fournisseur, sans cette page.
 *
 * <h2>Ce que cette page ne dit pas</h2>
 *
 * <p>Elle ne confirme jamais qu'un établissement existe, ni qu'une adresse y est connue. Une
 * saisie qui ne mène nulle part renvoie au mot de passe avec la même phrase, quelle qu'en soit
 * la raison — sans quoi la page deviendrait un annuaire de nos clients, interrogeable par
 * n'importe qui.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class SsoWebController {

    private final EtablissementRepository etablissementRepository;
    private final ConfigurationSsoRepository configurationSsoRepository;
    private final RegistreSsoTenant registre;

    @GetMapping("/sso")
    public String page(Model model) {
        model.addAttribute("erreurSso", null);
        return "public/sso";
    }

    @PostMapping("/sso")
    public String demarrer(@RequestParam(required = false) String identifiant, Model model) {
        String saisie = identifiant == null ? "" : identifiant.trim().toLowerCase(Locale.ROOT);
        if (saisie.isEmpty()) {
            model.addAttribute("erreurSso", "Indiquez votre adresse professionnelle.");
            return "public/sso";
        }

        Optional<String> slug = saisie.contains("@") ? parDomaine(saisie) : parSlug(saisie);
        if (slug.isEmpty()) {
            // Même phrase pour « établissement inconnu », « SSO non configuré », « plan
            // insuffisant » et « établissement suspendu ». Les distinguer transformerait cette
            // page en outil de reconnaissance.
            model.addAttribute("erreurSso",
                    "Aucune connexion par votre organisation n'est possible avec cette saisie. "
                    + "Utilisez votre mot de passe, ou demandez le lien à votre administrateur.");
            return "public/sso";
        }
        return "redirect:/oauth2/authorization/"
                + UriUtils.encodePathSegment(slug.get(), StandardCharsets.UTF_8);
    }

    /**
     * Lien direct, à distribuer aux agents d'un établissement.
     *
     * <p>Un identifiant qui n'ouvre rien ramène à la connexion par mot de passe plutôt que de
     * produire une page d'erreur : quelqu'un qui suit un lien périmé doit pouvoir entrer malgré
     * tout, et non se retrouver devant une impasse.
     */
    @GetMapping("/sso/{slug}")
    public String direct(@PathVariable String slug) {
        return parSlug(slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT))
                .map(s -> "redirect:/oauth2/authorization/"
                        + UriUtils.encodePathSegment(s, StandardCharsets.UTF_8))
                .orElse("redirect:/admin-login?sso_error=inconnu");
    }

    /**
     * Recueille les départs que le filtre OAuth2 a laissés passer.
     *
     * <p>Cette adresse est normalement traitée AVANT tout contrôleur, par le filtre qui
     * redirige vers le fournisseur d'identité. On n'arrive donc ici que lorsqu'il n'y avait
     * rien à résoudre — plan redescendu, abonnement expiré, configuration désactivée,
     * identifiant inconnu. Autant de situations ordinaires, dont aucune ne mérite la page
     * « erreur du serveur » qu'on obtiendrait sans ce filet (cf. {@code ResolveurDemandeSso}).
     */
    @GetMapping(ci.esatic.sigep.security.sso.ResolveurDemandeSso.BASE + "/{slug}")
    public String departImpossible() {
        return "redirect:/admin-login?sso_error=inconnu";
    }

    /** L'identifiant désigne-t-il un établissement prêt à recevoir une connexion SSO ? */
    private Optional<String> parSlug(String slug) {
        if (slug.isEmpty()) return Optional.empty();
        // registre.pour() applique TOUTES les conditions d'ouverture — plan, statut, abonnement,
        // configuration active, fournisseur joignable. On ne redirige donc jamais vers un
        // fournisseur qui refusera de toute façon.
        return registre.pour(slug).map(r -> slug);
    }

    /**
     * Du domaine de l'adresse vers l'établissement.
     *
     * <p>Les configurations actives sont parcourues en mémoire plutôt qu'interrogées en base par
     * ressemblance : les domaines y sont rangés en une seule colonne séparée par des virgules, et
     * un {@code LIKE '%esatic.ci%'} accepterait aussi bien {@code faux-esatic.ci}. La comparaison
     * exacte, domaine par domaine, est faite par l'entité elle-même — la même méthode que celle
     * qui tranche à la connexion, donc sans risque de divergence entre les deux.
     */
    private Optional<String> parDomaine(String email) {
        var trouves = new java.util.LinkedHashSet<String>();
        for (ConfigurationSso config : configurationSsoRepository.findByActifTrue()) {
            if (config.domainesAutorises().isEmpty()) continue;   // sans domaine, rien à retrouver
            if (!config.accepteLeDomaine(email)) continue;
            Optional<Etablissement> etablissement =
                    etablissementRepository.findById(config.getEtablissementId());
            if (etablissement.isEmpty()) continue;
            parSlug(etablissement.get().getSlug()).ifPresent(trouves::add);
        }

        // DEUX établissements qui revendiquent le même domaine : on ne tranche pas.
        //
        // Rien n'empêche un établissement d'inscrire dans sa liste le domaine d'un autre — nous
        // ne vérifions aucune propriété de domaine. En rendant le premier venu, cette page
        // enverrait les agents d'une école vers le fournisseur d'identité d'une autre, qui
        // verrait passer leurs adresses et pourrait leur présenter sa propre page de connexion.
        // Aucune session ne s'ouvrirait — le rattachement du compte y veille — mais l'hameçonnage
        // n'a pas besoin d'aller jusque-là.
        //
        // On renvoie donc au mot de passe, comme pour un domaine inconnu, et on le signale : une
        // revendication concurrente n'arrive pas par hasard.
        if (trouves.size() > 1) {
            log.warn("SSO : le domaine de « {} » est revendiqué par {} établissements ({}) — "
                    + "découverte refusée.", email, trouves.size(), trouves);
            return Optional.empty();
        }
        return trouves.stream().findFirst();
    }
}
