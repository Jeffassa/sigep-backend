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
        registry.addInterceptor(tenantInterceptor);
        registry.addInterceptor(abonnementInterceptor).addPathPatterns("/admin/**");
    }
}
