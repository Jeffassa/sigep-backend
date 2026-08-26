package ci.esatic.sigep.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Écriture ISOLÉE d'un identifiant anti-rejeu. {@code REQUIRES_NEW} pour ne PAS polluer la
 * transaction d'émargement en cours : en cas de course (clé insérée entre-temps), la
 * {@code DataIntegrityViolationException} fait rollback proprement CETTE transaction et remonte
 * à l'appelant, sans marquer la transaction d'émargement en rollback-only.
 *
 * <p>Bean distinct (et non méthode privée de {@link QrReplayGuard}) : l'auto-appel ne passe pas
 * par le proxy Spring, donc {@code REQUIRES_NEW} ne s'appliquerait pas.
 */
@Component
@RequiredArgsConstructor
class JtiConsommeWriter {

    private final JtiConsommeRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inserer(String cle, LocalDateTime expireLe) {
        repository.saveAndFlush(new JtiConsomme(cle, expireLe));
    }
}
