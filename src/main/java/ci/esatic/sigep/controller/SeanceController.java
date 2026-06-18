package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.request.SeanceRequest;
import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.SeanceResponse;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.service.SeanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/seances")
@RequiredArgsConstructor
public class SeanceController {

    private final SeanceService seanceService;
    private final EnseignantRepository enseignantRepository;

    // Séances du jour pour l'enseignant connecté
    @GetMapping("/aujourd-hui")
    public ResponseEntity<ApiResponse<List<SeanceResponse>>> getAujourdHui(
            @AuthenticationPrincipal User user) {
        Enseignant enseignant = getEnseignantByUser(user);
        List<SeanceResponse> seances = seanceService.getSeancesJour(enseignant.getId(), LocalDate.now());
        return ResponseEntity.ok(ApiResponse.success("Seances du jour", seances));
    }

    // Séances du jour encore à émarger (en cours + passées non émargées)
    @GetMapping("/a-emarger")
    public ResponseEntity<ApiResponse<List<SeanceResponse>>> getAEmarger(
            @AuthenticationPrincipal User user) {
        Enseignant enseignant = getEnseignantByUser(user);
        List<SeanceResponse> seances = seanceService.getSeancesAEmarger(enseignant.getId(), LocalDate.now());
        return ResponseEntity.ok(ApiResponse.success("Seances a emarger", seances));
    }

    // Séances de la semaine pour l'enseignant connecté
    @GetMapping("/semaine")
    public ResponseEntity<ApiResponse<List<SeanceResponse>>> getSemaine(
            @AuthenticationPrincipal User user) {
        Enseignant enseignant = getEnseignantByUser(user);
        List<SeanceResponse> seances = seanceService.getSeancesSemaine(enseignant.getId(), LocalDate.now());
        return ResponseEntity.ok(ApiResponse.success("Seances de la semaine", seances));
    }

    // Séances par date spécifique
    @GetMapping("/date/{date}")
    public ResponseEntity<ApiResponse<List<SeanceResponse>>> getByDate(
            @AuthenticationPrincipal User user,
            @PathVariable LocalDate date) {
        Enseignant enseignant = getEnseignantByUser(user);
        List<SeanceResponse> seances = seanceService.getSeancesJour(enseignant.getId(), date);
        return ResponseEntity.ok(ApiResponse.success("Seances du " + date, seances));
    }

    // Détail d'une séance
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SeanceResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Seance", seanceService.getById(id)));
    }

    // Admin : créer une séance individuelle
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SeanceResponse>> create(
            @Valid @RequestBody SeanceRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Seance creee", seanceService.create(request)));
    }

    // Admin : planning global du jour (tous enseignants)
    @GetMapping("/admin/aujourd-hui")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<SeanceResponse>>> getAdminAujourdHui() {
        return ResponseEntity.ok(ApiResponse.success("Planning global du jour",
                seanceService.getSeancesAdminJour(LocalDate.now())));
    }

    // Admin : planning global de la semaine
    @GetMapping("/admin/semaine")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<SeanceResponse>>> getAdminSemaine() {
        return ResponseEntity.ok(ApiResponse.success("Planning global de la semaine",
                seanceService.getSeancesAdminSemaine(LocalDate.now())));
    }

    // Admin : planning global d'une date précise
    @GetMapping("/admin/date/{date}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<SeanceResponse>>> getAdminByDate(
            @PathVariable LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success("Planning global du " + date,
                seanceService.getSeancesAdminJour(date)));
    }

    private Enseignant getEnseignantByUser(User user) {
        return enseignantRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "userId", user.getId()));
    }
}
