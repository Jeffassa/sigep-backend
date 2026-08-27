package ci.esatic.sigep.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentation OpenAPI (Swagger) avec authentification Bearer JWT documentée. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sigepOpenAPI() {
        final String scheme = "bearer-jwt";
        return new OpenAPI()
                .info(new Info()
                        .title("SIGEP API")
                        .version("v1")
                        .description("API SaaS multi-tenant — gestion des émargements, planning, "
                                + "rattrapages, statistiques et abonnements."))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(scheme));
    }
}
