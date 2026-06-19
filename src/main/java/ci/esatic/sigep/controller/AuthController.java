package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.request.InscriptionRequest;
import ci.esatic.sigep.dto.request.LoginRequest;
import ci.esatic.sigep.dto.request.RefreshRequest;
import ci.esatic.sigep.dto.request.RegisterEnseignantRequest;
import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.AuthResponse;
import ci.esatic.sigep.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Connexion réussie", response));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> inscription(@Valid @RequestBody InscriptionRequest request) {
        AuthResponse response = authService.inscription(request);
        return ResponseEntity.ok(ApiResponse.success(
                "Inscription enregistrée. En attente de validation par l'administration.", response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        AuthResponse response = authService.refresh(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Token rafraîchi", response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Déconnexion réussie", null));
    }

    @PostMapping("/register/enseignant")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AuthResponse>> registerEnseignant(
            @Valid @RequestBody RegisterEnseignantRequest request) {
        AuthResponse response = authService.registerEnseignant(request);
        return ResponseEntity.ok(ApiResponse.success("Compte enseignant créé", response));
    }
}
