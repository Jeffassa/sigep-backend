package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.RapportPdf;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.service.RapportService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Espace admin : rapports PDF (liste, génération, téléchargement, ZIP, synthèse Excel). */
@Controller
@RequiredArgsConstructor
public class RapportWebController {

    private final RapportService rapportService;
    private final EnseignantRepository enseignantRepository;
    private final PlanService planService;
    private final EtablissementCourantService etablissementCourantService;

    @GetMapping("/admin/rapports")
    public String rapports(@RequestParam(required = false) Long enseignantId,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin,
                           Model model) {
        model.addAttribute("rapports", rapportService.getRapportsFiltres(enseignantId, debut, fin));
        model.addAttribute("enseignants", enseignantRepository.findAll());
        model.addAttribute("fEnseignantId", enseignantId);
        model.addAttribute("fDebut", debut);
        model.addAttribute("fFin", fin);
        return "admin/rapports";
    }

    /** Télécharge tous les rapports (filtrés) dans une archive ZIP. Fonction Pro/Enterprise
     *  verrouillée côté serveur (le bouton masqué en Free ne suffit pas — URL directe possible). */
    @GetMapping("/admin/rapports/telecharger-zip")
    public ResponseEntity<byte[]> telechargerZip(
            @AuthenticationPrincipal User admin,
            @RequestParam(required = false) Long enseignantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin) {
        if (admin == null || !planService.estDisponible(etablissementCourantService.courant(),
                Feature.RAPPORTS_AVANCES)) {
            return ResponseEntity.status(403).build();
        }
        try {
            List<RapportPdf> rapports = rapportService.getRapportPdfsFiltres(enseignantId, debut, fin);
            if (rapports.isEmpty()) return ResponseEntity.noContent().build();
            byte[] zip = rapportService.genererZip(rapports);
            String nom = "rapports_" + LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy")) + ".zip";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nom + "\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(zip);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Export Excel de synthèse (une ligne par enseignant sur la période). Fonction
     *  Pro/Enterprise verrouillée côté serveur (pas seulement masquée dans l'interface). */
    @GetMapping("/admin/rapports/synthese")
    public ResponseEntity<byte[]> syntheseExcel(
            @AuthenticationPrincipal User admin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin) {
        if (admin == null || !planService.estDisponible(etablissementCourantService.courant(),
                Feature.RAPPORTS_AVANCES)) {
            return ResponseEntity.status(403).build();
        }
        try {
            LocalDate today = LocalDate.now();
            LocalDate d = debut != null ? debut : today.with(DayOfWeek.MONDAY);
            LocalDate f = fin != null ? fin : d.plusDays(6);
            byte[] xlsx = rapportService.genererSyntheseExcel(d, f);
            var fmt = DateTimeFormatter.ofPattern("ddMMyyyy");
            String nom = "synthese_" + d.format(fmt) + "-" + f.format(fmt) + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nom + "\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(xlsx);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/admin/rapports/generer")
    public String genererRapports(@RequestParam String debut,
                                   @RequestParam String fin,
                                   RedirectAttributes ra) {
        try {
            LocalDate d = LocalDate.parse(debut);
            LocalDate f = LocalDate.parse(fin);
            if (d.isAfter(f)) {
                ra.addFlashAttribute("error",
                        "La date de début doit être antérieure ou égale à la date de fin.");
                return "redirect:/admin/rapports";
            }
            List<?> result = rapportService.genererTousRapports(d, f);
            ra.addFlashAttribute("success", result.size() + " rapport(s) genere(s) avec succes.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Erreur lors de la generation : " + e.getMessage());
        }
        return "redirect:/admin/rapports";
    }

    @GetMapping("/admin/rapports/{id}/telecharger")
    public ResponseEntity<byte[]> telechargerRapport(@PathVariable Long id) {
        try {
            RapportPdf rapport = rapportService.getRapportEntity(id);
            byte[] bytes = rapportService.getRapportBytes(id);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + rapportService.nomFichierTelechargement(rapport) + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
