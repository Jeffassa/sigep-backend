package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.request.RattrapageRequest;
import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.RattrapageResponse;
import ci.esatic.sigep.entity.Salle;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.service.RattrapageService;
import ci.esatic.sigep.service.SalleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rattrapages")
@RequiredArgsConstructor
public class RattrapageController {

    private final RattrapageService rattrapageService;
    private final SalleService salleService;

    // Enseignant : soumettre une demande
    @PostMapping
    public ResponseEntity<ApiResponse<RattrapageResponse>> creerDemande(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RattrapageRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Demande envoyee",
                rattrapageService.creerDemande(user.getId(), request)));
    }

    // Enseignant : voir ses propres demandes
    @GetMapping("/mes-demandes")
    public ResponseEntity<ApiResponse<List<RattrapageResponse>>> getMesDemandes(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success("Mes demandes",
                rattrapageService.getMesDemandes(user.getId())));
    }

    // Admin : voir toutes les demandes
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<RattrapageResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success("Toutes les demandes",
                rattrapageService.getAllDemandes()));
    }

    // Admin : voir demandes en attente
    @GetMapping("/en-attente")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<RattrapageResponse>>> getEnAttente() {
        return ResponseEntity.ok(ApiResponse.success("Demandes en attente",
                rattrapageService.getDemandesEnAttente()));
    }

    // Admin : accepter une demande (crée automatiquement la séance)
    @PostMapping("/{id}/accepter")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RattrapageResponse>> accepter(
            @PathVariable Long id,
            @RequestParam Long salleId) {
        Salle salle = salleService.findById(salleId);
        return ResponseEntity.ok(ApiResponse.success("Demande acceptee - seance creee",
                rattrapageService.accepterAvecSalle(id, salle)));
    }

    // Admin : refuser une demande
    @PostMapping("/{id}/refuser")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RattrapageResponse>> refuser(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Demande refusee",
                rattrapageService.refuser(id)));
    }
}
