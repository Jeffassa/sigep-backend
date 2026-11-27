package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.JournalAgent;
import ci.esatic.sigep.repository.JournalAgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Journal de l'agent : chaque appel, réussi, refusé ou simulé.
 *
 * <p>Comme le journal de sécurité, il ne doit jamais faire échouer ce qu'il observe : une écriture
 * qui échoue est avalée et laissée dans les journaux du serveur.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JournalAgentService {

    private static final int MAX_RESUME = 500;

    private final JournalAgentRepository repository;

    @Value("${app.agent.journal.retention-jours:365}")
    private int retentionJours;

    public void enregistrer(Long etablissementId, Long cleId, Long userId, String methode, String chemin,
                            int statutHttp, boolean simulation, String resume) {
        enregistrer("AGENT", etablissementId, cleId, userId, methode, chemin, statutHttp, simulation, resume);
    }

    /** @param source AGENT (clé d'agent) ou ASSISTANT (assistant de la console, sans clé) */
    public void enregistrer(String source, Long etablissementId, Long cleId, Long userId, String methode, String chemin,
                            int statutHttp, boolean simulation, String resume) {
        try {
            repository.save(JournalAgent.builder()
                    .source(source)
                    .etablissementId(etablissementId)
                    .cleId(cleId)
                    .userId(userId)
                    .dateHeure(LocalDateTime.now())
                    .methode(methode)
                    .chemin(chemin.length() <= 255 ? chemin : chemin.substring(0, 255))
                    .statutHttp(statutHttp)
                    .simulation(simulation)
                    .resume(resume == null || resume.length() <= MAX_RESUME
                            ? resume : resume.substring(0, MAX_RESUME - 1) + "…")
                    .build());
        } catch (Exception e) {
            log.warn("Journal de l'agent : appel {} {} non enregistre ({})", methode, chemin, e.getMessage());
        }
    }

    @Scheduled(cron = "${app.agent.journal.purge-cron:0 40 3 * * *}")
    @SchedulerLock(name = "purgeJournalAgent", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void purger() {
        try {
            int supprimes = repository.purgerAvant(LocalDateTime.now().minusDays(retentionJours));
            if (supprimes > 0) {
                log.info("Journal de l'agent : {} ligne(s) purgee(s)", supprimes);
            }
        } catch (Exception e) {
            log.warn("Journal de l'agent : purge impossible ({})", e.getMessage());
        }
    }
}
