package ci.esatic.sigep.tenant;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Enregistre le TenantInterceptor sur toutes les routes. */
@Configuration
@RequiredArgsConstructor
public class TenantWebConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ORDRE CRITIQUE : le TenantInterceptor doit s'exécuter APRÈS OpenEntityManagerInView
        // (ordre 0), sinon le filtre Hibernate est activé sur une session jetable puis perdu
        // (les requêtes de la page tournent alors SANS isolation tenant). Ordre explicite
        // pour ne pas dépendre de l'ordre d'enregistrement des configurations.
        registry.addInterceptor(tenantInterceptor).order(1000);
    }
}
