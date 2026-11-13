package ci.esatic.sigep.service.plateforme;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.StatutEtablissement;
import ci.esatic.sigep.repository.EmargementRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import ci.esatic.sigep.service.AbonnementService;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ce que le gérant de la plateforme regarde chaque matin, au-delà du commercial : l'usage du
 * jour, les écoles qui démarrent, et ce qui arrive à échéance.
 */
@Service
@RequiredArgsConstructor
public class PilotagePlateformeService {

    /** Fenêtre de la tendance d'usage affichée. */
    public static final int JOURS_TENDANCE = 14;
    /** Une école reste dans « Mise en route » pendant ce délai après son inscription. */
    static final int JOURS_MISE_EN_ROUTE = 30;
    /** À partir de cette part du quota, une école gratuite devient candidate au plan payant. */
    static final int SEUIL_QUOTA_PCT = 80;
    private static final int LIGNES_MAX = 8;

    private final SeanceRepository seanceRepository;
    private final EmargementRepository emargementRepository;
    private final AbonnementService abonnementService;
    private final PlanService planService;

    /** L'usage du jour et sa tendance. */
    public record Pouls(long seancesPrevues, long seancesEmargees, long tauxPct, long emargementsDuJour,
                        long enseignantsActifs7j, long etablissementsActifs7j,
                        List<String> jours, List<Long> prevues, List<Long> emargees) {}

    /** Où en est une école récente : trois étapes, et celle qui la bloque. */
    public record MiseEnRoute(Etablissement e, long joursDepuisInscription, boolean enseignants,
                              boolean emploiDuTemps, boolean premierEmargement, String blocage, String contact) {
        public int etapesFaites() {
            return (enseignants ? 1 : 0) + (emploiDuTemps ? 1 : 0) + (premierEmargement ? 1 : 0);
        }
        public boolean terminee() { return etapesFaites() == 3; }
    }

    public record Echeance(Etablissement e, long jours, long montantMensuel) {}

    public record QuotaProche(Etablissement e, long enseignants, int max, long pct, long gainMensuel) {}

    public record Echeances(List<Echeance> sous7j, List<Echeance> sous30j, long montant30j,
                            List<QuotaProche> quotas, long gainPotentiel) {
        public boolean vide() { return sous7j.isEmpty() && sous30j.isEmpty() && quotas.isEmpty(); }
    }

    public Pouls pouls(LocalDate jour) {
        LocalDate debut = jour.minusDays(JOURS_TENDANCE - 1L);
        Map<LocalDate, long[]> parJour = activiteParJour(debut, jour);

        DateTimeFormatter etiquette = DateTimeFormatter.ofPattern("dd/MM");
        List<String> jours = new ArrayList<>();
        List<Long> prevues = new ArrayList<>();
        List<Long> emargees = new ArrayList<>();
        for (LocalDate d = debut; !d.isAfter(jour); d = d.plusDays(1)) {
            long[] v = parJour.getOrDefault(d, new long[] {0, 0});
            jours.add(d.format(etiquette));
            prevues.add(v[0]);
            emargees.add(v[1]);
        }
        long[] aujourdHui = parJour.getOrDefault(jour, new long[] {0, 0});
        LocalDateTime ilYA7Jours = jour.minusDays(6).atStartOfDay();
        return new Pouls(aujourdHui[0], aujourdHui[1], pourcentage(aujourdHui[1], aujourdHui[0]),
                emargementRepository.countByDateHeureBetween(jour.atStartOfDay(), jour.plusDays(1).atStartOfDay()),
                emargementRepository.enseignantsActifsDepuis(ilYA7Jours),
                emargementRepository.etablissementsActifsDepuis(ilYA7Jours),
                jours, prevues, emargees);
    }

    /** Séances prévues et émargées, jour par jour, tous établissements confondus. */
    public Map<LocalDate, long[]> activiteParJour(LocalDate debut, LocalDate fin) {
        Map<LocalDate, long[]> parJour = new HashMap<>();
        for (Object[] l : seanceRepository.activiteParJour(debut, fin)) {
            parJour.put((LocalDate) l[0], new long[] {((Number) l[1]).longValue(), ((Number) l[2]).longValue()});
        }
        return parJour;
    }

    /** Nombre de séances chargées par établissement : dit si l'emploi du temps a été importé. */
    public Map<Long, Long> seancesParEtablissement() {
        Map<Long, Long> seances = new HashMap<>();
        for (Object[] l : seanceRepository.countParEtablissement()) {
            if (l[0] != null) seances.put((Long) l[0], ((Number) l[1]).longValue());
        }
        return seances;
    }

    /**
     * Les écoles validées qui n'ont pas fini de démarrer, plus celles inscrites depuis moins de
     * trente jours. Les plus en retard d'abord : ce sont elles qu'il faut appeler.
     */
    public List<MiseEnRoute> miseEnRoute(List<Etablissement> clients, Map<Long, Long> enseignants,
                                         Map<Long, Long> seances, Map<Long, LocalDateTime> dernierEmargement,
                                         Map<Long, String> contacts, LocalDate jour) {
        List<MiseEnRoute> lignes = new ArrayList<>();
        for (Etablissement e : clients) {
            if (e.getStatut() != StatutEtablissement.VALIDE || !e.isActif()) continue;
            boolean aEnseignants = enseignants.getOrDefault(e.getId(), 0L) > 0;
            boolean aEmploiDuTemps = seances.getOrDefault(e.getId(), 0L) > 0;
            boolean aEmarge = dernierEmargement.get(e.getId()) != null;
            long age = e.getDateCreation() == null ? 0
                    : Math.max(0, ChronoUnit.DAYS.between(e.getDateCreation().toLocalDate(), jour));
            boolean terminee = aEnseignants && aEmploiDuTemps && aEmarge;
            if (terminee && age > JOURS_MISE_EN_ROUTE) continue;

            String blocage = !aEnseignants ? "Aucun enseignant créé"
                    : !aEmploiDuTemps ? "Emploi du temps non importé"
                    : !aEmarge ? "Aucun émargement encore"
                    : "Démarrage terminé";
            String contact = terminee ? null : contacts.get(e.getId());
            lignes.add(new MiseEnRoute(e, age, aEnseignants, aEmploiDuTemps, aEmarge, blocage, contact));
        }
        lignes.sort(Comparator.comparing(MiseEnRoute::terminee)
                .thenComparing(Comparator.comparingLong(MiseEnRoute::joursDepuisInscription).reversed()));
        return lignes.size() > LIGNES_MAX ? lignes.subList(0, LIGNES_MAX) : lignes;
    }

    /** Abonnements qui arrivent à terme sous 7 puis 30 jours, et écoles proches de leur quota. */
    public Echeances echeances(List<Etablissement> clients, Map<Long, Long> enseignants, Set<Long> expires) {
        List<Echeance> sous7j = new ArrayList<>();
        List<Echeance> sous30j = new ArrayList<>();
        List<QuotaProche> quotas = new ArrayList<>();
        long prixPro = planService.prixMensuel(Plan.PRO);

        for (Etablissement e : clients) {
            if (e.getStatut() != StatutEtablissement.VALIDE || !e.isActif() || expires.contains(e.getId())) continue;

            Long jours = abonnementService.joursAvantExpiration(e);
            if (jours != null && jours >= 0 && jours <= 30) {
                Echeance echeance = new Echeance(e, jours, planService.prixMensuel(e.getPlan()));
                (jours <= 7 ? sous7j : sous30j).add(echeance);
            }

            long effectif = enseignants.getOrDefault(e.getId(), 0L);
            if (e.getMaxEnseignants() > 0) {
                long pct = pourcentage(effectif, e.getMaxEnseignants());
                if (pct >= SEUIL_QUOTA_PCT) {
                    long gain = Math.max(0, prixPro - planService.prixMensuel(e.getPlan()));
                    quotas.add(new QuotaProche(e, effectif, e.getMaxEnseignants(), pct, gain));
                }
            }
        }
        sous7j.sort(Comparator.comparingLong(Echeance::jours));
        sous30j.sort(Comparator.comparingLong(Echeance::jours));
        quotas.sort(Comparator.comparingLong(QuotaProche::pct).reversed());
        long montant30j = sous7j.stream().mapToLong(Echeance::montantMensuel).sum()
                + sous30j.stream().mapToLong(Echeance::montantMensuel).sum();
        return new Echeances(sous7j, sous30j, montant30j, quotas,
                quotas.stream().mapToLong(QuotaProche::gainMensuel).sum());
    }

    static long pourcentage(long part, long total) {
        return total <= 0 ? 0 : Math.round(part * 100.0 / total);
    }
}
