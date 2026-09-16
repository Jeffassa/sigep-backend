package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.dto.response.LignePaie;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.service.ExportPaieService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
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
public class PaieWebController {

    private final ExportPaieService exportPaieService;
    private final PlanService planService;
    private final EtablissementCourantService etablissementCourantService;

    private static final DateTimeFormatter MOIS = DateTimeFormatter.ofPattern("yyyy-MM");

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
        return fichier(contenu, "paie_" + periode.format(MOIS) + ".csv",
                MediaType.parseMediaType("text/csv; charset=UTF-8"));
    }

    @GetMapping("/admin/paie/export.xlsx")
    public ResponseEntity<byte[]> excel(@AuthenticationPrincipal User admin,
                                        @RequestParam(required = false) String mois) {
        if (admin == null || !disponible()) return ResponseEntity.status(403).build();
        YearMonth periode = periode(mois);
        try {
            byte[] contenu = exportPaieService.versExcel(exportPaieService.calculer(periode), periode);
            return fichier(contenu, "paie_" + periode.format(MOIS) + ".xlsx",
                    MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
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
            return YearMonth.parse(mois.trim());
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
