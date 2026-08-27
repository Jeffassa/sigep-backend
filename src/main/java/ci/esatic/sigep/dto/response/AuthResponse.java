package ci.esatic.sigep.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String token;
    /** Jeton de rafraîchissement (longue durée) ; null à l'inscription en attente de validation. */
    private String refreshToken;
    @Builder.Default
    private String type = "Bearer";
    private Long id;
    private String email;
    private List<String> roles;
    private String nom;
    private String prenom;
    /** Nom de l'établissement (tenant) — permet à l'app mobile d'afficher SON établissement (E14). */
    private String etablissementNom;
}
