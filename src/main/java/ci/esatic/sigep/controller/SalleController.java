package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.request.SalleRequest;
import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.SalleResponse;
import ci.esatic.sigep.service.SalleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/salles")
@RequiredArgsConstructor
public class SalleController {

    private final SalleService salleService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SalleResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success("Liste des salles", salleService.findAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SalleResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Salle", salleService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SalleResponse>> create(@Valid @RequestBody SalleRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Salle creee", salleService.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SalleResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody SalleRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Salle mise a jour", salleService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        salleService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Salle supprimee", null));
    }
}
