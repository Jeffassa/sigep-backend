package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.EvenementSecurite;
import ci.esatic.sigep.entity.SeveriteEvenement;
import ci.esatic.sigep.entity.TypeEvenement;
import ci.esatic.sigep.repository.EvenementSecuriteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Journal des évènements de sécurité.
 *
 * <p><b>Ne doit jamais faire échouer ce qu'il observe.</b> Un journal est un témoin, pas un acteur :
 * si l'écriture échoue — base indisponible, colonne trop courte — la requête d'origine doit se
 * poursuivre exactement comme si de rien n'était, d'où le {@code try/catch} global.
 *
 * <p>L'écriture est {@code @Async}, ce qui répond aux deux besoins à la fois : elle s'exécute sur
 * un autre fil, donc en dehors de la transaction métier appelante — qu'un échec d'écriture ne peut
 * donc pas marquer comme devant être annulée — et elle ne ralentit pas la réponse, ce qui compte
 * sur les chemins déjà sous pression (un débit dépassé signifie beaucoup d'appels par seconde).
 * Chaque {@code save} porte sa propre transaction, celle du repository Spring Data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Lazy(false)
public class JournalSecuriteService {

    private final EvenementSecuriteRepository repository;

    /** Durée de conservation. Au-delà, l'intérêt d'analyse ne justifie plus le stockage. */
    @Value("${app.security.journal.retention-jours:60}")
    private int retentionJours;

    /** Longueur maximale du champ libre, alignée sur la colonne : on tronque au lieu d'échouer. */
    private static final int MAX_DETAILS = 500;

    @Async
    public void enregistrer(TypeEvenement type, SeveriteEvenement severite, String ip,
                            String identifiant, String chemin, String details) {
        enregistrer(type, severite, ip, identifiant, chemin, details, null);
    }

    @Async
    public void enregistrer(TypeEvenement type, SeveriteEvenement severite, String ip,
                            String identifiant, String chemin, String details, Long etablissementId) {
        try {
            repository.save(EvenementSecurite.builder()
                    .dateHeure(LocalDateTime.now())
                    .type(type)
                    .severite(severite)
                    .ip(tronquer(ip, 64))
                    .identifiant(tronquer(identifiant, 180))
                    .chemin(tronquer(chemin, 255))
                    .details(tronquer(details, MAX_DETAILS))
                    .etablissementId(etablissementId)
                    .build());
        } catch (Exception e) {
            // Volontairement avalé : voir la note de classe. On garde une trace fichier, sans plus.
            log.warn("Journal de securite : evenement {} non enregistre ({})", type, e.getMessage());
        }
    }

    /**
     * Purge quotidienne.
     *
     * <p>Verrou distribué : sur plusieurs instances, la purge ne doit s'exécuter qu'une fois,
     * sans quoi les suppressions concurrentes se marcheraient dessus.
     */
    @Scheduled(cron = "${app.security.journal.purge-cron:0 20 3 * * *}")
    @SchedulerLock(name = "purgeJournalSecurite", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void purger() {
        try {
            int supprimes = repository.purgerAvant(LocalDateTime.now().minusDays(retentionJours));
            if (supprimes > 0) {
                log.info("Journal de securite : {} evenement(s) purge(s) (retention {} jours)",
                        supprimes, retentionJours);
            }
        } catch (Exception e) {
            log.warn("Journal de securite : purge impossible ({})", e.getMessage());
        }
    }

    private static String tronquer(String valeur, int max) {
        if (valeur == null) return null;
        String v = valeur.trim();
        if (v.isEmpty()) return null;
        return v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }
}
