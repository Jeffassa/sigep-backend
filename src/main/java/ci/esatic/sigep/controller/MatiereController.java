package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.request.MatiereRequest;
import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.MatiereResponse;
import ci.esatic.sigep.service.MatiereService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/matieres")
@RequiredArgsConstructor
public class MatiereController {

    private final MatiereService matiereService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<MatiereResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success("Liste des matieres", matiereService.findAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MatiereResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Matiere", matiereService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MatiereResponse>> create(@Valid @RequestBody MatiereRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Matiere creee", matiereService.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MatiereResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody MatiereRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Matiere mise a jour", matiereService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        matiereService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Matiere supprimee", null));
    }
}
