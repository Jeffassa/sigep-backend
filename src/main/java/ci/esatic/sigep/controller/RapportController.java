package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.RapportResponse;
import ci.esatic.sigep.entity.RapportPdf;
import ci.esatic.sigep.service.RapportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/rapports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class RapportController {

    private final RapportService rapportService;
    private final ci.esatic.sigep.tenant.plan.PlanService planService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<RapportResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success("Rapports", rapportService.getAllRapports()));
    }

    // Génération manuelle pour tous les enseignants sur une période
    // Exemple : POST /api/rapports/generer?debut=01/06/2026&fin=07/06/2026
    @PostMapping("/generer")
    public ResponseEntity<ApiResponse<List<RapportResponse>>> genererManuellement(
            @RequestParam @DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate debut,
            @RequestParam @DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate fin) {
        if (debut.isAfter(fin)) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    "La date de début doit être antérieure ou égale à la date de fin."));
        }
        List<RapportResponse> rapports = rapportService.genererTousRapports(debut, fin);
        return ResponseEntity.ok(ApiResponse.success(
                rapports.size() + " rapport(s) genere(s)", rapports));
    }

    // Télécharger un rapport PDF unique
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) throws Exception {
        RapportPdf rapport = rapportService.getRapportEntity(id);
        byte[] bytes = rapportService.getRapportBytes(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + rapportService.nomFichierTelechargement(rapport) + "\"")
                .body(bytes);
    }

    // Télécharger plusieurs rapports en archive ZIP — fonction Pro/Enterprise (verrou serveur)
    @PostMapping("/bulk-download")
    public ResponseEntity<byte[]> bulkDownload(
            @org.springframework.security.core.annotation.AuthenticationPrincipal ci.esatic.sigep.entity.User admin,
            @RequestBody List<Long> ids) throws Exception {
        planService.exiger(admin == null ? null : admin.getEtablissement(),
                ci.esatic.sigep.tenant.plan.Feature.RAPPORTS_AVANCES);
        List<RapportPdf> rapports = rapportService.getRapportsByIds(ids);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (RapportPdf rapport : rapports) {
                byte[] pdfBytes = rapportService.getRapportBytes(rapport.getId());
                zos.putNextEntry(new ZipEntry(rapport.getNomFichier()));
                zos.write(pdfBytes);
                zos.closeEntry();
            }
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"rapports_sigep.zip\"")
                .body(baos.toByteArray());
    }
}
