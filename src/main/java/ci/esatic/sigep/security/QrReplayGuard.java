package ci.esatic.sigep.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Anti-rejeu des tokens QR d'émargement, PERSISTANT en base (E4).
 * Un même token (jti) ne peut être consommé qu'une seule fois PAR enseignant : cela empêche
 * le rejeu d'une capture d'écran et l'émargement multiple avec un seul scan, tout en laissant
 * plusieurs enseignants scanner le même QR universel.
 *
 * <p>L'état est stocké en base (table {@code jti_consommes}) : il survit aux redémarrages et
 * fonctionne en multi-instance (l'ancienne {@code ConcurrentHashMap} ne couvrait ni l'un ni
 * l'autre). L'unicité est garantie par la clé primaire ; la consommation est atomique.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QrReplayGuard {

    private final JtiConsommeRepository repository;
    private final JtiConsommeWriter writer;

    // Rétention d'un jti consommé : doit couvrir la fenêtre de rejeu la plus longue, à savoir
    // la synchro hors-ligne (app.emargement.hors-ligne.max-delai-jours) + marge. Défaut 72 h.
    @Value("${app.security.jti-retention-hours:72}")
    private long retentionHeures = 72;

    /**
     * Tente de consommer le token pour cet enseignant.
     * @return true si première utilisation (autorisé), false si déjà utilisé (rejeu).
     */
    public boolean tryConsume(Long enseignantId, String jti) {
        if (jti == null || jti.isBlank()) return true; // pas de jti → anti-rejeu non applicable
        String cle = enseignantId + ":" + jti;
        if (repository.existsById(cle)) return false;  // déjà consommé
        try {
            writer.inserer(cle, LocalDateTime.now().plusHours(retentionHeures));
            return true;
        } catch (DataIntegrityViolationException e) {
            // Course : une requête concurrente a inséré la même clé entre le existsById et le save.
            return false;
        }
    }

    /** Purge quotidienne des identifiants dont la rétention est dépassée. */
    @Scheduled(cron = "${app.security.jti-purge-cron:0 45 3 * * *}")
    @Transactional
    public void purgerExpires() {
        int supprimes = repository.deleteExpired(LocalDateTime.now());
        if (supprimes > 0) log.debug("Anti-rejeu QR : {} jti expires purges", supprimes);
    }
}
