package ci.esatic.sigep.config;

import ci.esatic.sigep.tenant.TenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Exécuteur des tâches {@code @Async} avec PROPAGATION du contexte.
 *
 * <p>Par défaut, une méthode {@code @Async} s'exécute sur un thread neuf qui n'hérite NI du
 * {@link TenantContext} (ThreadLocal) NI du {@code SecurityContext}. Or MailService et d'autres
 * traitements asynchrones peuvent lire/écrire des entités multi-tenant : sans propagation, ils
 * verraient/estamperaient les données SANS isolation (fuite latente relevée à l'audit).
 *
 * <p>Ce {@link TaskDecorator} capture le tenant + la sécurité du thread appelant et les restaure
 * dans le thread d'exécution, puis les nettoie systématiquement (pools réutilisés).
 */
@Configuration
public class AsyncConfig {

    /** Bean nommé "taskExecutor" : utilisé par défaut par {@code @Async}. */
    @Bean
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("sigep-async-");
        executor.setTaskDecorator(contextPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    private TaskDecorator contextPropagatingDecorator() {
        return runnable -> {
            Long tenant = TenantContext.get();
            SecurityContext securityContext = SecurityContextHolder.getContext();
            return () -> {
                try {
                    if (tenant != null) TenantContext.set(tenant);
                    if (securityContext != null) SecurityContextHolder.setContext(securityContext);
                    runnable.run();
                } finally {
                    TenantContext.clear();
                    SecurityContextHolder.clearContext();
                }
            };
        };
    }
}
