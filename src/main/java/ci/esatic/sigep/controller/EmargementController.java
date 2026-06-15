package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.request.EmargementRequest;
import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.EmargementResponse;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.service.EmargementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/emargements")
@RequiredArgsConstructor
public class EmargementController {

    private final EmargementService emargementService;

    @PostMapping
    public ResponseEntity<ApiResponse<EmargementResponse>> emarger(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody EmargementRequest request) {
        EmargementResponse response = emargementService.emarger(user.getId(), request);
        return ResponseEntity.ok(ApiResponse.success("Emargement valide avec succes", response));
    }

    @GetMapping("/historique")
    public ResponseEntity<ApiResponse<List<EmargementResponse>>> getHistorique(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success("Historique", emargementService.getHistorique(user.getId())));
    }
}
