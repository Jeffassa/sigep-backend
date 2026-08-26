package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.request.ClasseRequest;
import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.ClasseResponse;
import ci.esatic.sigep.service.ClasseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/classes")
@RequiredArgsConstructor
public class ClasseController {

    private final ClasseService classeService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<ClasseResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success("Liste des classes", classeService.findAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ClasseResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Classe", classeService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ClasseResponse>> create(@Valid @RequestBody ClasseRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Classe creee", classeService.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ClasseResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody ClasseRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Classe mise a jour", classeService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        classeService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Classe supprimee", null));
    }
}
