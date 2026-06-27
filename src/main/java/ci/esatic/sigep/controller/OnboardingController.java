package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.request.InscriptionEtablissementRequest;
import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.AuthResponse;
import ci.esatic.sigep.service.OnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints publics d'onboarding SaaS (inscription d'un établissement). */
@RestController
@RequestMapping("/api/saas")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @PostMapping("/etablissements")
    public ResponseEntity<ApiResponse<AuthResponse>> inscrireEtablissement(
            @Valid @RequestBody InscriptionEtablissementRequest request) {
        AuthResponse response = onboardingService.inscrire(request);
        return ResponseEntity.ok(ApiResponse.success(
                "Établissement créé. Bienvenue sur SIGEP !", response));
    }
}
