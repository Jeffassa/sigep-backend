package ci.esatic.sigep.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration CORS pour l'API. Exposée comme {@link CorsConfigurationSource} et câblée
 * dans la chaîne Spring Security (SecurityConfig : {@code http.cors(...)}) — et non comme
 * filtre autonome, qui s'exécutait APRÈS la sécurité (preflight authentifié -> 401).
 *
 * <p>SECURITE : origines en LISTE FIXE (app.cors.allowed-origins), jamais de wildcard réfléchi.
 * {@code allowCredentials=false} : l'API s'authentifie par en-tête {@code Authorization: Bearer}
 * (aucun cookie d'auth), donc les credentials cross-origin sont inutiles et dangereux à autoriser.
 */
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:http://localhost:8080}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        // Auth par Bearer sans cookie -> pas de credentials cross-origin.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
