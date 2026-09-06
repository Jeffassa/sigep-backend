package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.EvenementSecurite;
import ci.esatic.sigep.entity.SeveriteEvenement;
import ci.esatic.sigep.entity.TypeEvenement;
import ci.esatic.sigep.repository.EvenementSecuriteRepository;
import ci.esatic.sigep.security.CompteurTrafic;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Console de surveillance du super-administrateur.
 *
 * <p>Réservée à {@code /plateforme/**}, donc au seul {@code ROLE_SUPER_ADMIN} : ces évènements
 * traversent tous les établissements et révèlent, par nature, ce qui se passe chez les autres.
 * Un administrateur d'établissement n'y a pas accès.
 *
 * <p><b>Le journal lui-même est en lecture seule</b> — aucune route ne permet d'en effacer une
 * ligne. Un journal modifiable depuis l'interface ne prouve plus rien : c'est ce qu'un intrus
 * viderait en premier. Les actions offertes ici agissent sur l'ÉTABLISSEMENT (couper ses sessions,
 * renouveler sa clé d'écran), jamais sur les traces, et sont elles-mêmes consignées.
 */
@Controller
@RequiredArgsConstructor
public class SecuriteWebController {

    private static final int PAR_PAGE = 40;

    private final EvenementSecuriteRepository evenementRepository;
    private final CompteurTrafic compteurTrafic;
    private final ci.esatic.sigep.repository.EtablissementRepository etablissementRepository;
    private final ci.esatic.sigep.service.RefreshTokenService refreshTokenService;
    private final ci.esatic.sigep.service.JournalSecuriteService journalSecurite;

    @GetMapping("/plateforme/securite")
    public String securite(@RequestParam(required = false) String severite,
                           @RequestParam(required = false) String type,
                           @RequestParam(required = false) Long etablissement,
                           @RequestParam(defaultValue = "0") int page,
                           Model model) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), PAR_PAGE);
        SeveriteEvenement filtreSeverite = parseSeverite(severite);
        TypeEvenement filtreType = parseType(type);

        Page<EvenementSecurite> evenements;
        if (etablissement != null) {
            evenements = evenementRepository.findByEtablissementIdOrderByDateHeureDesc(etablissement, pageable);
        } else if (filtreSeverite != null) {
            evenements = evenementRepository.findBySeveriteOrderByDateHeureDesc(filtreSeverite, pageable);
        } else if (filtreType != null) {
            evenements = evenementRepository.findByTypeOrderByDateHeureDesc(filtreType, pageable);
        } else {
            evenements = evenementRepository.findAllByOrderByDateHeureDesc(pageable);
        }

        LocalDateTime hier = LocalDateTime.now().minusHours(24);
        LocalDateTime semaine = LocalDateTime.now().minusDays(7);

        model.addAttribute("evenements", evenements);
        model.addAttribute("severiteActive", filtreSeverite);
        model.addAttribute("typeActif", filtreType);
        model.addAttribute("severites", SeveriteEvenement.values());

        // Indicateurs sur 24 h : la fenêtre qui répond à « que s'est-il passé cette nuit ? ».
        model.addAttribute("total24h", evenementRepository.countByDateHeureAfter(hier));
        model.addAttribute("critiques24h",
                evenementRepository.countBySeveriteAndDateHeureAfter(SeveriteEvenement.CRITIQUE, hier));
        model.addAttribute("connexionsRefusees24h",
                evenementRepository.countByTypeAndDateHeureAfter(TypeEvenement.CONNEXION_ADMIN_ECHOUEE, hier));
        model.addAttribute("debitsDepasses24h",
                evenementRepository.countByTypeAndDateHeureAfter(TypeEvenement.DEBIT_DEPASSE, hier));

        // Bilan par établissement : c'est ce qui transforme une constatation en action.
        model.addAttribute("bilanEtablissements", construireBilan(semaine));
        model.addAttribute("etablissementActif",
                etablissement == null ? null : etablissementRepository.findById(etablissement).orElse(null));

        model.addAttribute("sourcesInsistantes",
                evenementRepository.sourcesLesPlusInsistantes(semaine, PageRequest.of(0, 8)));
        model.addAttribute("repartition", evenementRepository.repartitionParType(semaine));

        // Trafic : en mémoire, donc limité à l'heure écoulée et à cette instance.
        List<CompteurTrafic.Minute> trafic = compteurTrafic.derniereHeure();
        model.addAttribute("trafic", trafic);
        model.addAttribute("traficTotal", compteurTrafic.totalRequetes());
        model.addAttribute("traficRefus", compteurTrafic.totalRefus());
        model.addAttribute("traficPic", compteurTrafic.picParMinute());
        model.addAttribute("traficMax", Math.max(1, compteurTrafic.picParMinute()));

        return "plateforme/securite";
    }

    /**
     * Croise les compteurs avec les noms d'établissement.
     *
     * <p>L'agrégation est faite en base, mais {@code EvenementSecurite} ne porte qu'un
     * identifiant d'établissement, pas une relation : joindre en JPQL obligerait à coupler le
     * journal au reste du modèle, alors qu'il doit rester lisible même si un établissement a
     * disparu depuis. Les noms sont donc résolus ici, en une seule requête.
     */
    private List<LigneBilan> construireBilan(java.time.LocalDateTime depuis) {
        var bilans = evenementRepository.bilanParEtablissement(depuis);
        if (bilans.isEmpty()) return List.of();

        var noms = new java.util.HashMap<Long, Etablissement>();
        etablissementRepository.findAllById(bilans.stream()
                        .map(EvenementSecuriteRepository.BilanEtablissement::getEtablissementId).toList())
                .forEach(e -> noms.put(e.getId(), e));

        return bilans.stream().map(b -> {
            Etablissement e = noms.get(b.getEtablissementId());
            return new LigneBilan(
                    b.getEtablissementId(),
                    // Un établissement supprimé laisse ses évènements derrière lui : on l'annonce
                    // plutôt que d'afficher une ligne muette.
                    e == null ? "Établissement supprimé" : e.getNomEffectif(),
                    e == null ? null : e.getSlug(),
                    b.getTotal(), b.getCritiques(), b.getDernier());
        }).toList();
    }

    /**
     * Coupe toutes les sessions ouvertes d'un établissement.
     *
     * <p>Après une fuite d'identifiants ou un poste compromis, révoquer session par session ne sert
     * à rien : il faut fermer la maison d'un coup. Chacun devra se reconnecter avec son mot de
     * passe — ce qui suppose que celui-ci ait été changé si c'est lui qui a fuité.
     */
    @PostMapping("/plateforme/securite/etablissements/{id}/sessions")
    public String couperLesSessions(@PathVariable Long id, RedirectAttributes ra,
                                    java.security.Principal principal) {
        Etablissement etab = etablissementRepository.findById(id).orElse(null);
        if (etab == null) {
            ra.addFlashAttribute("error", "Établissement introuvable.");
            return "redirect:/plateforme/securite";
        }
        int comptes = refreshTokenService.revoquerToutEtablissement(id);
        journalSecurite.enregistrer(TypeEvenement.REMEDIATION, SeveriteEvenement.INFO, null,
                principal == null ? null : principal.getName(), "/plateforme/securite",
                "Sessions coupees pour " + etab.getNomEffectif() + " (" + comptes + " compte(s))", id);
        ra.addFlashAttribute("success",
                "Sessions coupées : " + comptes + " compte(s) de « " + etab.getNomEffectif()
                        + " » devront se reconnecter.");
        return "redirect:/plateforme/securite?etablissement=" + id;
    }

    /**
     * Renouvelle la clé de l'écran de salle d'un établissement.
     *
     * <p>Réponse directe à un QR qui circule : la clé fuitée cesse de fonctionner, et les écrans
     * déjà ouverts s'éteignent. C'est l'effet recherché — ils devront être rouverts depuis
     * l'administration de l'établissement.
     */
    @PostMapping("/plateforme/securite/etablissements/{id}/cle-kiosque")
    public String renouvelerCleKiosque(@PathVariable Long id, RedirectAttributes ra,
                                       java.security.Principal principal) {
        Etablissement etab = etablissementRepository.findById(id).orElse(null);
        if (etab == null) {
            ra.addFlashAttribute("error", "Établissement introuvable.");
            return "redirect:/plateforme/securite";
        }
        etab.setKioskKey(java.util.UUID.randomUUID().toString().replace("-", ""));
        etablissementRepository.save(etab);
        journalSecurite.enregistrer(TypeEvenement.REMEDIATION, SeveriteEvenement.INFO, null,
                principal == null ? null : principal.getName(), "/plateforme/securite",
                "Cle kiosque renouvelee pour " + etab.getNomEffectif(), id);
        ra.addFlashAttribute("success",
                "Clé d'écran renouvelée pour « " + etab.getNomEffectif()
                        + " ». Les écrans ouverts doivent être relancés.");
        return "redirect:/plateforme/securite?etablissement=" + id;
    }

    /** Ligne du bilan, prête pour l'affichage. */
    public record LigneBilan(Long id, String nom, String slug, long total, long critiques,
                             java.time.LocalDateTime dernier) {}

    /** Un filtre inconnu ne doit pas produire d'erreur : on retombe simplement sur « tout ». */
    private SeveriteEvenement parseSeverite(String valeur) {
        if (valeur == null || valeur.isBlank()) return null;
        try {
            return SeveriteEvenement.valueOf(valeur.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private TypeEvenement parseType(String valeur) {
        if (valeur == null || valeur.isBlank()) return null;
        try {
            return TypeEvenement.valueOf(valeur.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
