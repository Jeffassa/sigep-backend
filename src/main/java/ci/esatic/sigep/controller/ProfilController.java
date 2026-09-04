package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.request.ChangerMotDePasseRequest;
import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Espace « mon compte » de l'utilisateur connecté (app mobile).
 *
 * <p><b>Pourquoi pas dans {@code AuthController} ?</b> Parce que {@code /api/auth/**} est déclaré
 * {@code permitAll()} dans la configuration de sécurité. Un changement de mot de passe placé là
 * serait atteignable sans jeton — et surtout, l'identité de l'appelant ne serait pas établie,
 * donc impossible de savoir DE QUI changer le mot de passe. Sous {@code /api/profil}, c'est la
 * règle {@code anyRequest().authenticated()} qui s'applique.
 */
@RestController
@RequestMapping("/api/profil")
@RequiredArgsConstructor
public class ProfilController {

    private final AuthService authService;

    @PostMapping("/mot-de-passe")
    public ResponseEntity<ApiResponse<Void>> changerMotDePasse(
            Authentication authentication,
            @Valid @RequestBody ChangerMotDePasseRequest request) {
        // getName() = l'e-mail porté par le JWT validé (cf. JwtAuthenticationFilter). L'identité
        // vient donc du jeton, jamais du corps de la requête : un utilisateur ne peut changer
        // que SON propre mot de passe, même en falsifiant la charge utile.
        authService.changerMotDePasse(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponse.success(
                "Mot de passe modifié. Reconnectez-vous avec le nouveau mot de passe.", null));
    }
}
