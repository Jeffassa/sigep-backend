package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Seance;
import ci.esatic.sigep.entity.StatutSeance;
import ci.esatic.sigep.repository.SeanceRepository;
import ci.esatic.sigep.tenant.TenantTaskRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Relances automatiques par e-mail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RelanceService {

    private static final DateTimeFormatter HEURE_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final SeanceRepository seanceRepository;
    private final MailService mailService;
    private final TenantTaskRunner tenantTaskRunner;

    /** Tous les jours à 19h : relance les enseignants ayant des séances non émargées le jour même.
     *  Exécuté PAR ÉTABLISSEMENT (contexte tenant) pour rester cloisonné. */
    @Scheduled(cron = "0 0 19 * * *")
    public void relancerSeancesNonEmargees() {
        LocalDate today = LocalDate.now();
        tenantTaskRunner.pourChaqueTenantActif(etab -> relancerPourTenantCourant(today, etab.getEmailFrom()));
    }

    /** Logique exécutée dans le contexte (transaction + filtre) d'un établissement. */
    private void relancerPourTenantCourant(LocalDate today, String expediteurTenant) {
        List<Seance> nonEmargees = seanceRepository.findAllByDateOrderByHeureDebutAsc(today).stream()
                .filter(s -> s.getStatut() != StatutSeance.EMARGE)
                .collect(Collectors.toList());
        if (nonEmargees.isEmpty()) return;

        Map<Enseignant, List<Seance>> parProf = nonEmargees.stream()
                .collect(Collectors.groupingBy(Seance::getEnseignant));

        int notifies = 0;
        for (Map.Entry<Enseignant, List<Seance>> e : parProf.entrySet()) {
            Enseignant ens = e.getKey();
            String email = ens.getUser() != null ? ens.getUser().getEmail() : null;
            if (email == null) continue;
            List<String> lignes = e.getValue().stream()
                    .map(s -> "• " + s.getMatiere().getLibelle()
                            + " (" + s.getHeureDebut().format(HEURE_FMT) + "-" + s.getHeureFin().format(HEURE_FMT) + ")"
                            + " — salle " + s.getSalle().getLibelle())
                    .collect(Collectors.toList());
            mailService.notifierSeancesNonEmargees(expediteurTenant, email, ens.getPrenom(), lignes);
            notifies++;
        }
        log.info("Relance e-mail séances non émargées ({}) : {} enseignant(s) notifié(s)", today, notifies);
    }
}
