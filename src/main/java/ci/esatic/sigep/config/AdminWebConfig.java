package ci.esatic.sigep.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class AdminWebConfig implements WebMvcConfigurer {
    private final AdminOtpInterceptor adminOtpInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminOtpInterceptor)
                .addPathPatterns("/admin/**", "/plateforme/**")
                .excludePathPatterns("/admin-login", "/admin-otp");
    }
}