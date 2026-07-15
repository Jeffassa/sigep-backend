package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Relance (dunning) automatique des abonnements arrivant à expiration (E15).
 * Sans surveillance manuelle : chaque jour, on notifie l'admin de chaque établissement
 * dont l'abonnement expire à J-7 / J-3 / J-1 ou le jour même. Complète le bandeau in-app.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AbonnementRelanceService {

    private final EtablissementRepository etablissementRepository;
    private final UserRepository userRepository;
    private final AbonnementService abonnementService;
    private final MailService mailService;

    /** Tous les jours à 8h30 : relances d'expiration d'abonnement. */
    @Scheduled(cron = "0 30 8 * * *")
    public void relancerAbonnements() {
        int notifies = 0;
        for (Etablissement e : etablissementRepository.findByActifTrue()) {
            Long jours = abonnementService.joursAvantExpiration(e);
            if (jours == null) continue;                 // pas d'expiration (Free / illimité)
            // Points de relance : J-7, J-3, J-1 et le jour de l'expiration (pas de spam quotidien).
            if (jours == 7 || jours == 3 || jours == 1 || jours == 0) {
                var admin = userRepository.findFirstByEtablissementIdOrderByIdAsc(e.getId()).orElse(null);
                if (admin != null && admin.getEmail() != null) {
                    mailService.notifierExpirationAbonnement(
                            admin.getEmail(), e.getNomEffectif(), jours, e.getDateExpiration());
                    notifies++;
                }
            }
        }
        if (notifies > 0) log.info("Relance abonnements : {} établissement(s) notifié(s)", notifies);
    }
}
