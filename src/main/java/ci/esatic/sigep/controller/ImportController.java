package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.service.ImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ImportController {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L; // 5 MB
    private static final String[] ALLOWED_EXTENSIONS = {".csv", ".xlsx"};

    private final ImportService importService;

    @PostMapping("/api/admin/import/planning")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importerPlanningAdmin(
            @RequestParam("file") MultipartFile file) {
        ResponseEntity<ApiResponse<Map<String, Object>>> validation = validateFile(file);
        if (validation != null) return validation;
        try {
            Map<String, Object> result = importService.importerPlanning(file);
            return ResponseEntity.ok(ApiResponse.success("Planning importe avec succes", result));
        } catch (Exception e) {
            log.error("Erreur import planning admin : {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Le fichier contient des données invalides ou non reconnues."));
        }
    }

    @PostMapping("/api/import/mon-planning")
    @PreAuthorize("hasRole('ENSEIGNANT') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importerMonPlanning(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User user) {
        ResponseEntity<ApiResponse<Map<String, Object>>> validation = validateFile(file);
        if (validation != null) return validation;
        try {
            Map<String, Object> result = importService.importerMonPlanning(file, user.getId());
            return ResponseEntity.ok(ApiResponse.success("Emploi du temps importe avec succes", result));
        } catch (Exception e) {
            log.error("Erreur import planning enseignant userId={} : {}", user.getId(), e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Le fichier contient des données invalides ou non reconnues."));
        }
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Fichier manquant ou vide."));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Fichier trop volumineux (max 5 MB)."));
        }
        String filename = file.getOriginalFilename();
        if (filename == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Nom de fichier invalide."));
        }
        boolean extensionOk = false;
        for (String ext : ALLOWED_EXTENSIONS) {
            if (filename.toLowerCase().endsWith(ext)) { extensionOk = true; break; }
        }
        if (!extensionOk) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Format non supporté. Utilisez .csv ou .xlsx."));
        }
        return null;
    }
}
