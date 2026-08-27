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

    public long compter() {
        return seanceRepository.countSeancesNonEmargees(LocalDate.now(), LocalTime.now())
                + rattrapageRepository.countByStatut(StatutDemande.EN_ATTENTE)
                + enseignantRepository.countByStatut(StatutEnseignant.PENDING)
                // Hors-ligne EN ATTENTE de validation (actionnable) — pas le cumul historique.
                + emargementRepository.countByHorsLigneTrueAndValideFalse();
    }
}
