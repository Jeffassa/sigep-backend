package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.EnseignantResponse;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.service.EnseignantService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/enseignants")
@RequiredArgsConstructor
public class EnseignantController {

    private final EnseignantService enseignantService;

    // Profil de l'enseignant actuellement connecté
    @GetMapping("/moi")
    public ResponseEntity<ApiResponse<EnseignantResponse>> getMonProfil(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success("Mon profil",
                enseignantService.getByUserId(user.getId())));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<EnseignantResponse>>> getAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String departement,
            @RequestParam(required = false) StatutEnseignant statut,
            @PageableDefault(size = 10) Pageable pageable) {

        Page<EnseignantResponse> page = enseignantService.searchEnseignants(search, departement, statut, pageable);
        return ResponseEntity.ok(ApiResponse.success("Liste des enseignants", page));
    }

    // Réservé à l'ADMIN : un enseignant consulte son propre profil via /moi (évite l'énumération
    // des profils de collègues du même établissement).
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<EnseignantResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Enseignant trouvé", enseignantService.getById(id)));
    }

    @PatchMapping("/{id}/statut")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<EnseignantResponse>> updateStatut(
            @PathVariable Long id,
            @RequestParam StatutEnseignant statut) {
        return ResponseEntity.ok(ApiResponse.success("Statut mis à jour",
                enseignantService.updateStatut(id, statut)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        enseignantService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Enseignant supprimé", null));
    }
}
