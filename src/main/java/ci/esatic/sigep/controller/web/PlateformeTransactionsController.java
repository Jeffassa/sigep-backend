package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.PaiementIntent;
import ci.esatic.sigep.entity.StatutIntent;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.PaiementIntentRepository;
import ci.esatic.sigep.service.TransactionsPlateformeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Espace plateforme : toutes les transactions de paiement, avec leur statut, et la décision
 * manuelle sur celles que le fournisseur n'a pas tranchées.
 */
@Controller
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequestMapping("/plateforme/transactions")
public class PlateformeTransactionsController {

    /** Au-delà, la liste se resserre avec un filtre ou une recherche. */
    static final int AFFICHAGE_MAX = 300;

    private final PaiementIntentRepository intents;
    private final EtablissementRepository etablissementRepository;
    private final TransactionsPlateformeService service;

    @GetMapping
    public String liste(@RequestParam(required = false) StatutIntent statut,
                        @RequestParam(required = false) String q, Model model) {
        List<PaiementIntent> toutes = intents.findAllByOrderByDateCreationDesc();
        Map<Long, Etablissement> etabs = etablissementRepository.findAll().stream()
                .collect(Collectors.toMap(Etablissement::getId, Function.identity()));

        Map<StatutIntent, Long> compte = new EnumMap<>(StatutIntent.class);
        for (StatutIntent s : StatutIntent.values()) compte.put(s, 0L);
        toutes.forEach(i -> compte.merge(i.getStatut(), 1L, Long::sum));

        String recherche = q == null ? "" : normaliser(q.trim());
        List<PaiementIntent> filtrees = toutes.stream()
                .filter(i -> statut == null || i.getStatut() == statut)
                .filter(i -> recherche.isEmpty() || normaliser(i.getReference()).contains(recherche)
                        || (etabs.containsKey(i.getEtablissementId())
                            && normaliser(etabs.get(i.getEtablissementId()).getNom()).contains(recherche)))
                .toList();

        long encaisse = toutes.stream().filter(i -> i.getStatut() == StatutIntent.REUSSI)
                .mapToLong(PaiementIntent::getMontantAttendu).sum();

        model.addAttribute("transactions", filtrees.stream().limit(AFFICHAGE_MAX).toList());
        model.addAttribute("tronquee", filtrees.size() > AFFICHAGE_MAX);
        model.addAttribute("nbFiltrees", filtrees.size());
        model.addAttribute("etabs", etabs);
        model.addAttribute("compte", compte.entrySet().stream()
                .collect(Collectors.toMap(x -> x.getKey().name(), Map.Entry::getValue)));
        model.addAttribute("nbPayees", compte.get(StatutIntent.REUSSI));
        model.addAttribute("nbAVerifier", compte.get(StatutIntent.A_VERIFIER));
        model.addAttribute("nbEnCours", compte.get(StatutIntent.EN_COURS));
        model.addAttribute("total", toutes.size());
        model.addAttribute("encaisse", encaisse);
        // « À vérifier » en tête : c'est l'onglet qui demande une action.
        model.addAttribute("statuts", List.of(StatutIntent.A_VERIFIER, StatutIntent.EN_COURS,
                StatutIntent.REUSSI, StatutIntent.ECHOUE, StatutIntent.EXPIRE));
        model.addAttribute("statutChoisi", statut);
        model.addAttribute("q", q == null ? "" : q.trim());
        return "plateforme/transactions";
    }

    @GetMapping("/{reference}")
    public String detail(@PathVariable String reference, Model model, RedirectAttributes ra) {
        PaiementIntent i = intents.findByReference(reference).orElse(null);
        if (i == null) {
            ra.addFlashAttribute("erreur", "Transaction introuvable : " + reference);
            return "redirect:/plateforme/transactions";
        }
        model.addAttribute("t", i);
        model.addAttribute("etab", etablissementRepository.findById(i.getEtablissementId()).orElse(null));
        model.addAttribute("paiement", service.paiementCredite(i).orElse(null));
        model.addAttribute("confirmable", TransactionsPlateformeService.CONFIRMABLES.contains(i.getStatut()));
        model.addAttribute("classable", TransactionsPlateformeService.CLASSABLES_NON_PAYEES.contains(i.getStatut()));
        model.addAttribute("autres", intents.findByEtablissementIdOrderByDateCreationDesc(i.getEtablissementId())
                .stream().filter(x -> !x.getId().equals(i.getId())).limit(6).toList());
        return "plateforme/transaction";
    }

    @PostMapping("/{reference}/payee")
    public String confirmerPayee(@PathVariable String reference,
                                 @RequestParam(required = false) Long montant,
                                 @RequestParam(required = false) String note,
                                 @AuthenticationPrincipal User auteur, RedirectAttributes ra) {
        try {
            service.confirmerPayee(reference, montant, note, email(auteur));
            ra.addFlashAttribute("ok", "Transaction confirmée payée : l'abonnement est crédité.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("erreur", e.getMessage());
            ra.addFlashAttribute("brouillon", note);
        }
        return "redirect:/plateforme/transactions/" + enc(reference);
    }

    @PostMapping("/{reference}/non-payee")
    public String classerNonPayee(@PathVariable String reference,
                                  @RequestParam(required = false) String note,
                                  @AuthenticationPrincipal User auteur, RedirectAttributes ra) {
        try {
            service.classerNonPayee(reference, note, email(auteur));
            ra.addFlashAttribute("ok", "Transaction classée non payée.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("erreur", e.getMessage());
            ra.addFlashAttribute("brouillon", note);
        }
        return "redirect:/plateforme/transactions/" + enc(reference);
    }

    private static String email(User u) {
        return u == null ? "super administrateur" : u.getEmail();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String normaliser(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
