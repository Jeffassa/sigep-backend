package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.StatutDemande;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.repository.DemandeRattrapageRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Compteur d'alertes admin (badge de la barre supérieure) : séances non émargées à régulariser
 * + demandes de rattrapage en attente + comptes enseignants en attente de validation.
 * Extrait pour être partagé entre l'advice (badge sur toutes les vues admin) et le dashboard.
 */
@Service
@RequiredArgsConstructor
public class AlerteService {

    private final SeanceRepository seanceRepository;
    private final DemandeRattrapageRepository rattrapageRepository;
    private final EnseignantRepository enseignantRepository;
    private final ci.esatic.sigep.repository.EmargementRepository emargementRepository;

    /**
     * Le compteur est affiché sur toutes les pages de l'administration : le recalculer à chaque
     * affichage coûtait cinq requêtes par page. Il est gardé vingt secondes par établissement
     * (cache {@link ci.esatic.sigep.config.CacheConfig#ALERTES}) et effacé dès qu'une action de
     * cet établissement peut l'avoir changé ({@link #oublier}). Sans établissement en contexte,
     * rien n'est gardé : la clé serait commune à tous.
     */
    @org.springframework.cache.annotation.Cacheable(
            cacheNames = ci.esatic.sigep.config.CacheConfig.ALERTES,
            key = "T(ci.esatic.sigep.tenant.TenantContext).get()",
            condition = "T(ci.esatic.sigep.tenant.TenantContext).get() != null")
    public long compter() {
        return calculer();
    }

    /** À appeler après toute action d'un établissement : son compteur sera recalculé. */
    @org.springframework.cache.annotation.CacheEvict(
            cacheNames = ci.esatic.sigep.config.CacheConfig.ALERTES,
            key = "#p0", condition = "#p0 != null")
    public void oublier(Long tenant) {
        // L'effacement est fait par l'annotation.
    }

    private long calculer() {
        return seanceRepository.countSeancesNonEmargees(LocalDate.now(), LocalTime.now())
                + rattrapageRepository.countByStatut(StatutDemande.EN_ATTENTE)
                + enseignantRepository.countByStatut(StatutEnseignant.PENDING)
                // Hors-ligne EN ATTENTE de validation (actionnable) — pas le cumul historique.
                + emargementRepository.countByHorsLigneTrueAndValideFalse()
                + seanceRepository.countByStatut(ci.esatic.sigep.entity.StatutSeance.A_VALIDER);
    }
}
