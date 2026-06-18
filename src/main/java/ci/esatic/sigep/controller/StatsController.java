package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.StatsResponse;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    // Statistiques de l'enseignant connecté
    @GetMapping("/moi")
    public ResponseEntity<ApiResponse<StatsResponse>> mesStats(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success("Mes statistiques",
                statsService.getStatsEnseignant(user.getId())));
    }
}
