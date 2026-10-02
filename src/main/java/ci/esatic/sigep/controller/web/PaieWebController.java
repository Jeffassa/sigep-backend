package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.dto.response.LignePaie;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.service.ExportPaieService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Espace admin : export des heures faites, pour le service paie.
 *
 * <p>Un écran qui montre les lignes avant de les télécharger. Un fichier de paie qu'on découvre
 * une fois ouvert dans un tableur se corrige trop tard : la vérification doit pouvoir se faire
 * ici, sur la période choisie, avant que le chiffre parte au service paie.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class PaieWebController {

    private final ExportPaieService exportPaieService;
    private final PlanService planService;
    private final EtablissementCourantService etablissementCourantService;

    private static final DateTimeFormatter MOIS = DateTimeFormatter.ofPattern("yyyy-MM");

    /** Fenetre de mois acceptee : au-dela, les calculs de mois voisin debordent. */
    private static final YearMonth PLANCHER = YearMonth.of(2000, 1);
    private static final YearMonth PLAFOND = YearMonth.of(2100, 12);

    @GetMapping("/admin/paie")
    public String paie(@RequestParam(required = false) String mois, Model model) {
        YearMonth periode = periode(mois);
        boolean disponible = disponible();

        model.addAttribute("mois", periode.format(MOIS));
        model.addAttribute("moisLibelle", libelle(periode));
        model.addAttribute("moisPrecedent", periode.minusMonths(1).format(MOIS));
        model.addAttribute("moisSuivant", periode.plusMonths(1).format(MOIS));
        model.addAttribute("paieDisponible", disponible);

        if (disponible) {
            List<LignePaie> lignes = exportPaieService.calculer(periode);
            model.addAttribute("lignes", lignes);
            model.addAttribute("totaux", exportPaieService.totaux(lignes));
        } else {
            model.addAttribute("lignes", List.of());
            model.addAttribute("totaux", new ExportPaieService.TotauxPaie(0, 0, 0, 0, 0, 0, 0));
        }
        return "admin/paie";
    }

    /**
     * CSV. L'URL porte l'extension à dessein : la navigation instantanée laisse passer en
     * navigation réelle tout ce qui ressemble à un fichier, sans quoi le téléchargement
     * atterrirait en mémoire au lieu du disque.
     */
    @GetMapping("/admin/paie/export.csv")
    public ResponseEntity<byte[]> csv(@AuthenticationPrincipal User admin,
                                      @RequestParam(required = false) String mois) {
        if (admin == null || !disponible()) return ResponseEntity.status(403).build();
        YearMonth periode = periode(mois);
        byte[] contenu = exportPaieService.versCsv(exportPaieService.calculer(periode), periode);
        return fichier(contenu, nomFichier(periode, "csv"),
                MediaType.parseMediaType("text/csv; charset=UTF-8"));
    }

    @GetMapping("/admin/paie/export.xlsx")
    public ResponseEntity<byte[]> excel(@AuthenticationPrincipal User admin,
                                        @RequestParam(required = false) String mois) {
        if (admin == null || !disponible()) return ResponseEntity.status(403).build();
        YearMonth periode = periode(mois);
        try {
            byte[] contenu = exportPaieService.versExcel(exportPaieService.calculer(periode), periode);
            return fichier(contenu, nomFichier(periode, "xlsx"),
                    MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        } catch (Exception e) {
            // Sans cette trace, un echec — y compris le refus de la garde tenant — devient un
            // 500 muet sur un fichier de paie : personne ne peut dire pourquoi il manque.
            log.error("Export paie XLSX impossible pour {} : {}", periode, e.toString(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Nom du fichier telecharge, portant l'etablissement.
     *
     * <p>Sans lui, deux ecoles produisent le meme « paie_2026-03.csv » : dans le dossier de
     * telechargements d'un gestionnaire qui suit plusieurs etablissements, ou dans la boite du
     * service paie d'un groupe, le second ecrase le premier sans un mot.
     *
     * <p>Le libelle est passe au meme tamis que les noms de rapports : lettres, chiffres, tiret
     * et point uniquement. Aucun texte libre n'atteint l'en-tete HTTP.
     */
    private String nomFichier(YearMonth periode, String extension) {
        Etablissement etablissement = etablissementCourantService.courant();
        String qui = etablissement == null ? null : etablissement.getSlug();
        String tamise = qui == null ? "" : qui.replaceAll("[^A-Za-z0-9_-]", "");
        return "paie_" + (tamise.isEmpty() ? "" : tamise + "_") + periode.format(MOIS) + "." + extension;
    }

    private boolean disponible() {
        return planService.estDisponible(etablissementCourantService.courant(), Feature.EXPORT_PAIE);
    }

    /**
     * Le mois demandé, ou le mois en cours.
     *
     * <p>Une valeur illisible ramène au mois courant plutôt qu'à une erreur : le paramètre
     * vient d'une URL, et une URL se recopie mal. Le nom de fichier est ensuite construit à
     * partir de cette valeur normalisée, jamais de la chaîne reçue — aucun texte de
     * l'utilisateur n'atteint l'en-tête HTTP.
     */
    private static YearMonth periode(String mois) {
        if (mois == null || mois.isBlank()) return YearMonth.now();
        try {
            YearMonth demande = YearMonth.parse(mois.trim());
            // Les bornes de YearMonth vont a +/- 999 999 999 ans. A l'extremite, le simple
            // calcul du mois suivant, qui alimente la fleche de navigation, deborde et rend
            // une erreur 500. Aucune paie ne se fait hors de cette fenetre.
            return demande.isBefore(PLANCHER) || demande.isAfter(PLAFOND) ? YearMonth.now() : demande;
        } catch (Exception e) {
            return YearMonth.now();
        }
    }

    private static String libelle(YearMonth periode) {
        String nom = periode.getMonth().getDisplayName(TextStyle.FULL, Locale.FRENCH);
        return Character.toUpperCase(nom.charAt(0)) + nom.substring(1) + " " + periode.getYear();
    }

    private static ResponseEntity<byte[]> fichier(byte[] contenu, String nom, MediaType type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nom + "\"")
                .contentType(type)
                .body(contenu);
    }
}
