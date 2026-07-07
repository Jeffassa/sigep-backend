package ci.esatic.sigep.config;

import ci.esatic.sigep.security.JwtAuthenticationFilter;
import ci.esatic.sigep.security.UserDetailsServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final UserDetailsServiceImpl userDetailsService;
    private final ci.esatic.sigep.security.RateLimitFilter rateLimitFilter;

    /** SECURITE : en production, exiger HTTPS (rejette/redirige le trafic non sécurisé).
     *  Désactivé par défaut pour ne pas casser le dev en HTTP local. */
    @org.springframework.beans.factory.annotation.Value("${app.security.require-https:false}")
    private boolean requireHttps;

    /** Chaîne 1 : Interface web admin — form login + sessions HTTP */
    @Bean
    @Order(1)
    public SecurityFilterChain adminWebFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/admin/**", "/admin-login")
                // CSRF activé : Thymeleaf injecte automatiquement le token dans th:action
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin-login").permitAll()
                        .anyRequest().hasRole("ADMIN")
                )
                .formLogin(form -> form
                        .loginPage("/admin-login")
                        .loginProcessingUrl("/admin-login")
                        .defaultSuccessUrl("/admin/dashboard", true)
                        .failureUrl("/admin-login?error=true")
                )
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin-login?logout=true")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                )
                .authenticationProvider(authenticationProvider())
                // Rate-limiting (anti-bruteforce du login admin + filet global) avant l'auth form.
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class);
        if (requireHttps) {
            http.requiresChannel(c -> c.anyRequest().requiresSecure());
        }
        return http.build();
    }

    /** Chaîne 2 : API REST — JWT stateless */
    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Pages publiques SaaS (accueil + inscription) + ressources statiques
                        .requestMatchers("/", "/inscription", "/error", "/favicon.ico").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        // Onboarding SaaS : inscription publique d'un établissement (rate-limité)
                        .requestMatchers("/api/saas/**").permitAll()
                        .requestMatchers("/api/qr/display/**").permitAll()
                        // Sondes de supervision publiques ; metrics reste authentifié
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                // Requête API non authentifiée (token absent/expiré/invalide) → 401 (et non 403),
                // ce qui permet au client mobile de déclencher le rafraîchissement du token.
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authEx) -> {
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"success\":false,\"message\":\"Authentification requise\"}");
                }))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        if (requireHttps) {
            http.requiresChannel(c -> c.anyRequest().requiresSecure());
        }
        return http.build();
    }

    /** Provider username/password (DAO). Volontairement NON exposé en @Bean : sinon Spring
     *  signale "Global AuthenticationManager configured with an AuthenticationProvider bean.
     *  UserDetailsService beans will not be used...". Sans bean global, le manager global se
     *  base sur UserDetailsService + PasswordEncoder (cf. authenticationManager), et chaque
     *  chaîne HTTP reçoit explicitement ce provider ci-dessous. */
    private AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
