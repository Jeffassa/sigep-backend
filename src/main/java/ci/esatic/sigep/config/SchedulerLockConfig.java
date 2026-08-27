package ci.esatic.sigep.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Verrou distribué des tâches planifiées (ShedLock). En déploiement multi-instance,
 * garantit qu'un cron (relances, rapports, dunning) s'exécute sur UN SEUL nœud —
 * plus de doubles e-mails ni de doubles rapports. Sans effet en mono-instance.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT15M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()   // horloge de la base : robuste au décalage d'horloge entre nœuds
                        .build());
    }
}
