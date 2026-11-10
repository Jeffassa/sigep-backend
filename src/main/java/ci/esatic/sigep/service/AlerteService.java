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

    /** Durée pendant laquelle un compteur déjà calculé est resservi. */
    private static final long DUREE_MS = 20_000;

    /** Par établissement : { valeur, instant du calcul }. */
    private final java.util.concurrent.ConcurrentHashMap<Long, long[]> memoire = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Le compteur est affiché sur toutes les pages de l'administration : le recalculer à chaque
     * affichage coûtait cinq requêtes par page. Il est donc gardé vingt secondes par
     * établissement, et oublié dès qu'une action de cet établissement peut l'avoir changé
     * ({@link #oublier}). Sans établissement en contexte, rien n'est gardé.
     */
    public long compter() {
        Long tenant = ci.esatic.sigep.tenant.TenantContext.get();
        if (tenant == null) return calculer();
        long maintenant = System.currentTimeMillis();
        long[] connu = memoire.get(tenant);
        if (connu != null && maintenant - connu[1] < DUREE_MS) return connu[0];
        long valeur = calculer();
        memoire.put(tenant, new long[] {valeur, maintenant});
        return valeur;
    }

    /** À appeler après toute action d'un établissement : son compteur sera recalculé. */
    public void oublier(Long tenant) {
        if (tenant != null) memoire.remove(tenant);
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
