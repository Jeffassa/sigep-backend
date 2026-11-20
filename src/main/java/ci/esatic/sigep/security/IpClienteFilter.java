package ci.esatic.sigep.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Donne à toute l'application la même IP cliente que {@link ClientIpResolver}.
 *
 * <p>Placé juste après {@code ForwardedHeaderFilter}, qui fixe {@code getRemoteAddr()} à la valeur
 * la plus à gauche de X-Forwarded-For, c'est-à-dire celle que l'appelant écrit lui-même. Les
 * journaux, les détails d'authentification et les webhooks lisent ensuite l'IP corrigée.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IpClienteFilter extends OncePerRequestFilter {

    private final ClientIpResolver resolver;

    public IpClienteFilter(ClientIpResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String ip = resolver.resolve(request);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getRemoteAddr() { return ip; }
            @Override public String getRemoteHost() { return ip; }
        }, response);
    }
}
