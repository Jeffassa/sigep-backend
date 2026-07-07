package ci.esatic.sigep.tenant;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Enregistre le TenantInterceptor (toutes routes) et le blocage d'abonnement (/admin/**). */
@Configuration
@RequiredArgsConstructor
public class TenantWebConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;
    private final ci.esatic.sigep.controller.web.AbonnementInterceptor abonnementInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ORDRE CRITIQUE : le TenantInterceptor doit s'exécuter APRÈS OpenEntityManagerInView
        // (ordre 0), sinon le filtre Hibernate est activé sur une session jetable puis perdu
        // (les requêtes de la page tournent alors SANS isolation tenant). Ordres explicites
        // pour ne pas dépendre de l'ordre d'enregistrement des configurations.
        registry.addInterceptor(tenantInterceptor).order(1000);
        registry.addInterceptor(abonnementInterceptor).addPathPatterns("/admin/**").order(1100);
    }
}
