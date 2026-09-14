package ci.esatic.sigep.security;

import ci.esatic.sigep.service.CleApiService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import ci.esatic.sigep.repository.EtablissementRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authentifie un appel de l'API publique à partir de sa clé.
 *
 * <p>La clé est attendue dans l'en-tête {@code X-API-Key}. Elle identifie l'établissement, et
 * lui seul : l'identité posée ici porte cet établissement, et l'intercepteur multi-tenant s'en
 * sert pour restreindre toutes les lectures de la requête.
 *
 * <p>Le plan est vérifié à CHAQUE appel, et non à l'émission de la clé. Un établissement qui
 * repasse au plan gratuit perd ainsi l'accès immédiatement, sans qu'on ait à parcourir ses clés
 * pour les révoquer.
 */
@Component
@RequiredArgsConstructor
public class CleApiAuthFilter extends OncePerRequestFilter {

    public static final String ENTETE = "X-API-Key";

    private final CleApiService cleApiService;
    private final PlanService planService;
    private final EtablissementRepository etablissementRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/public/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse,
                                    FilterChain chaine) throws ServletException, IOException {
        String cle = requete.getHeader(ENTETE);
        if (cle == null || cle.isBlank()) {
            refuser(reponse, 401, "Clé d'API manquante. Ajoutez l'en-tête " + ENTETE + ".");
            return;
        }

        var trouvee = cleApiService.reconnaitre(cle);
        if (trouvee.isEmpty()) {
            // Même réponse pour une clé inconnue et une clé révoquée : distinguer les deux
            // apprendrait à qui tâtonne laquelle de ses tentatives a existé un jour.
            refuser(reponse, 401, "Clé d'API inconnue ou révoquée.");
            return;
        }

        var cleApi = trouvee.get();
        var etablissement = etablissementRepository.findById(cleApi.getEtablissementId()).orElse(null);
        if (etablissement == null) {
            refuser(reponse, 403, "Établissement introuvable.");
            return;
        }
        if (!planService.estDisponible(etablissement, Feature.API_PUBLIQUE)) {
            refuser(reponse, 403,
                    "L'API est comprise dans le plan Enterprise. Votre plan actuel ne l'inclut pas.");
            return;
        }

        var principal = new CleApiPrincipal(cleApi.getId(), cleApi.getEtablissementId(), cleApi.getLibelle());
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_API")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            chaine.doFilter(requete, reponse);
        } finally {
            // L'identité ne doit pas survivre à la requête : le thread est réutilisé.
            SecurityContextHolder.clearContext();
        }
    }

    private void refuser(HttpServletResponse reponse, int code, String message) throws IOException {
        reponse.setStatus(code);
        reponse.setContentType("application/json;charset=UTF-8");
        reponse.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
    }
}
