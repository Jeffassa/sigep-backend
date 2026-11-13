package ci.esatic.sigep.service.plateforme;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.StatutEtablissement;
import ci.esatic.sigep.repository.EmargementRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import ci.esatic.sigep.service.AbonnementService;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * La cellule d'analyse de la plateforme : cinq analystes qui lisent les données de SIGEP et en
 * tirent, chacun dans son domaine, des constats et des prévisions rédigés.
 *
 * <p>Ce sont des calculs, pas un service d'intelligence artificielle : tout se fait ici, sur
 * nos serveurs, et aucune donnée ne quitte le système. Chaque analyste dit sur quoi il s'appuie
 * et quel crédit accorder à ce qu'il avance — une plateforme jeune n'a pas encore assez
 * d'historique pour des prévisions fermes, et l'écran ne doit pas le cacher.
 */
@Service
@RequiredArgsConstructor
public class AnalystesService {

    public static final String ALERTE = "alerte";
    public static final String ATTENTION = "attention";
    public static final String BON = "bon";
    public static final String INFO = "info";

    /** Horizon des prévisions, en mois. */
    public static final int HORIZON_MOIS = 3;
    static final int RISQUE_ELEVE = 60;
    static final int RISQUE_MOYEN = 35;

    private final SeanceRepository seanceRepository;
    private final EmargementRepository emargementRepository;
    private final PilotagePlateformeService pilotage;
    private final AbonnementService abonnementService;
    private final PlanService planService;

    /**
     * Nombre d'établissements que SIGEP peut viser. La plateforme ne peut pas le deviner : il
     * vient d'une source extérieure, que le gérant renseigne. À zéro, la part de marché n'est
     * pas calculée.
     */
    @Value("${app.marche.etablissements-cibles:0}")
    private long marcheCible;

    public record Constat(String niveau, String texte) {}

    public record Analyste(String nom, String role, String verdict,
                           List<Constat> constats, String fiabilite, String methode) {}

    /** Probabilité qu'un établissement quitte SIGEP, sur 100, et ce qui la motive. */
    public record RisqueDepart(Etablissement e, int score, String niveau, String raison, long montantMensuel) {}

    public record Rapport(List<Analyste> analystes, List<String> moisFuturs, List<Long> nouveauxPrevus,
                          List<RisqueDepart> risques) {}

    /** Ce que le tableau de bord a déjà calculé et que les analystes réutilisent. */
    public record Entrees(LocalDate jour, List<Etablissement> clients, Map<Long, Long> enseignants,
                          Map<Long, long[]> activite30j, Map<Long, LocalDateTime> dernierEmargement,
                          Set<Long> expires, List<Long> nouveauxParMois, List<Long> encaisseParMois,
                          long gainPotentielQuotas) {}

    /** Durée pendant laquelle une analyse déjà faite est resservie. */
    static final long DUREE_MS = 5 * 60_000;

    private volatile Rapport dernier;
    private volatile long calculeA;

    /**
     * L'analyse lit plusieurs mois de données pour des constats qui ne bougent pas d'une minute
     * à l'autre : elle est refaite au plus toutes les cinq minutes. Le tableau de bord, lui, est
     * rouvert à chaque clic.
     */
    public Rapport analyser(Entrees in) {
        Rapport connu = dernier;
        if (connu != null && System.currentTimeMillis() - calculeA < DUREE_MS) return connu;
        Rapport neuf = calculer(in);
        dernier = neuf;
        calculeA = System.currentTimeMillis();
        return neuf;
    }

    Rapport calculer(Entrees in) {
        LocalDate jour = in.jour();
        Map<Long, long[]> activiteAvant = new HashMap<>();
        for (Object[] l : seanceRepository.activiteParEtablissement(jour.minusDays(61), jour.minusDays(31))) {
            if (l[0] != null) activiteAvant.put((Long) l[0],
                    new long[] {((Number) l[1]).longValue(), ((Number) l[2]).longValue()});
        }

        List<Etablissement> enService = in.clients().stream()
                .filter(e -> e.getStatut() == StatutEtablissement.VALIDE && e.isActif()).toList();
        List<Etablissement> payants = enService.stream()
                .filter(e -> e.getPlan() != Plan.FREE && !in.expires().contains(e.getId())).toList();
        long mrr = payants.stream().mapToLong(e -> planService.prixMensuel(e.getPlan())).sum();

        List<RisqueDepart> risques = risques(enService, in, activiteAvant);

        // Le mois en cours n'est pas fini : on le ramène à un mois entier avant de tracer la tendance.
        List<Long> serie = new ArrayList<>(in.nouveauxParMois());
        long observeCeMois = serie.isEmpty() ? 0 : serie.get(serie.size() - 1);
        double rythmeAvant = serie.size() < 2 ? 0 : Prevision.moyenneRecente(serie.subList(0, serie.size() - 1), 3);
        long projeteCeMois = projectionFinDeMois(observeCeMois, rythmeAvant, jour);
        if (!serie.isEmpty()) serie.set(serie.size() - 1, projeteCeMois);
        List<Long> nouveauxPrevus = Prevision.prolonger(serie, HORIZON_MOIS);
        double rythme = Prevision.moyenneRecente(serie, 3);

        List<String> moisFuturs = new ArrayList<>();
        YearMonth courant = YearMonth.from(jour);
        for (int i = 1; i <= HORIZON_MOIS; i++) moisFuturs.add(etiquetteCourte(courant.plusMonths(i)));

        List<Analyste> analystes = List.of(
                croissance(in, serie, observeCeMois, projeteCeMois, nouveauxPrevus, rythme, courant),
                revenus(in, enService, payants, mrr, risques, nouveauxPrevus),
                fidelisation(enService, risques),
                usage(in, activiteAvant, jour),
                marche(in, enService, payants, rythme));
        return new Rapport(analystes, moisFuturs, nouveauxPrevus, risques);
    }

    // ─── Croissance ───────────────────────────────────────────────────────────

    private Analyste croissance(Entrees in, List<Long> serie, long observe, long projete,
                                List<Long> prevus, double rythme, YearMonth courant) {
        long attendus = prevus.stream().mapToLong(Long::longValue).sum();
        long total = in.clients().size();
        String echeance = courant.plusMonths(HORIZON_MOIS).getMonth().getDisplayName(TextStyle.FULL, Locale.FRENCH);
        double pente = Prevision.tendance(serie).pente();

        List<Constat> constats = new ArrayList<>();
        String verdict;
        if (total == 0) {
            verdict = "Aucun établissement inscrit : rien à prévoir pour l'instant.";
        } else {
            verdict = prudence(Prevision.fiabilite(in.nouveauxParMois()),
                    "Environ " + attendus + " nouveau(x) établissement(s) d'ici fin " + echeance
                    + ", soit " + (total + attendus) + " au total.");
            constats.add(new Constat(INFO, "Rythme récent : " + unDecimal(rythme)
                    + " inscription(s) par mois sur les trois derniers mois."));
            constats.add(new Constat(pente > 0.25 ? BON : pente < -0.25 ? ATTENTION : INFO,
                    pente > 0.25 ? "Les inscriptions accélèrent d'un mois sur l'autre."
                            : pente < -0.25 ? "Les inscriptions ralentissent d'un mois sur l'autre."
                            : "Les inscriptions sont stables d'un mois sur l'autre."));
            constats.add(new Constat(INFO, "Ce mois-ci : " + observe + " inscription(s) à ce jour"
                    + (projete > observe ? ", " + projete + " attendue(s) à la fin du mois." : ".")));
        }
        return new Analyste("Croissance", "Prévoit les inscriptions", verdict, constats,
                Prevision.fiabilite(in.nouveauxParMois()),
                "Tendance linéaire sur les inscriptions des huit derniers mois, prolongée de trois mois.");
    }

    /**
     * Ramène le compte du mois en cours à un mois entier : ce qui est déjà observé, plus le rythme
     * des mois précédents sur les jours qui restent. Extrapoler le seul début de mois ferait
     * d'une inscription isolée une tendance.
     */
    static long projectionFinDeMois(long observe, double rythmeMensuel, LocalDate jour) {
        double resteDuMois = (jour.lengthOfMonth() - jour.getDayOfMonth()) / (double) jour.lengthOfMonth();
        return observe + Math.round(Math.max(0, rythmeMensuel) * resteDuMois);
    }

    /** Une prévision tirée de trop peu d'historique se présente comme telle. */
    private static String prudence(String fiabilite, String verdict) {
        if (!"faible".equals(fiabilite)) return verdict;
        return "À titre indicatif : " + Character.toLowerCase(verdict.charAt(0)) + verdict.substring(1);
    }

    // ─── Revenus ──────────────────────────────────────────────────────────────

    private Analyste revenus(Entrees in, List<Etablissement> enService, List<Etablissement> payants, long mrr,
                             List<RisqueDepart> risques, List<Long> nouveauxPrevus) {
        long prixPro = planService.prixMensuel(Plan.PRO);
        double arpu = payants.isEmpty() ? prixPro : mrr / (double) payants.size();
        double partPayante = enService.isEmpty() ? 0 : payants.size() / (double) enService.size();
        long perteAttendue = Math.round(risques.stream()
                .mapToDouble(r -> r.score() / 100.0 * r.montantMensuel()).sum());
        long gainNouveaux = Math.round(nouveauxPrevus.stream().mapToLong(Long::longValue).sum() * partPayante * arpu);
        long mrrPrevu = Math.max(0, mrr - perteAttendue + gainNouveaux);

        long renouvellements = 0;
        long montantRenouvellements = 0;
        for (Etablissement e : payants) {
            Long jours = abonnementService.joursAvantExpiration(e);
            if (jours != null && jours >= 0 && jours <= 30) {
                renouvellements++;
                montantRenouvellements += planService.prixMensuel(e.getPlan());
            }
        }

        List<Constat> constats = new ArrayList<>();
        String verdict;
        if (payants.isEmpty()) {
            verdict = "Aucun abonnement payant en cours : le revenu dépend des premières conversions.";
            if (!enService.isEmpty()) {
                constats.add(new Constat(INFO, enService.size() + " établissement(s) en service sur le plan gratuit : "
                        + "chaque passage au plan Pro rapporte " + argent(prixPro) + " par mois."));
            }
        } else {
            verdict = prudence(fiabiliteParEffectif(payants.size()),
                    "Revenu mensuel attendu dans trois mois : " + argent(mrrPrevu)
                    + " (aujourd'hui " + argent(mrr) + ").");
            constats.add(new Constat(INFO, payants.size() + " client(s) payant(s) sur " + enService.size()
                    + " en service, soit " + Math.round(partPayante * 100) + " %, pour "
                    + argent(Math.round(arpu)) + " par client et par mois."));
            if (perteAttendue > 0) {
                constats.add(new Constat(ATTENTION, "Perte probable : " + argent(perteAttendue)
                        + " par mois, d'après le risque de départ de chaque client."));
            }
            constats.add(new Constat(renouvellements > 0 ? ATTENTION : INFO, renouvellements > 0
                    ? renouvellements + " renouvellement(s) à obtenir sous 30 jours, pour " + argent(montantRenouvellements) + "."
                    : "Aucun renouvellement à obtenir sous 30 jours."));
        }
        if (in.gainPotentielQuotas() > 0) {
            constats.add(new Constat(BON, "Écoles proches de leur quota : jusqu'à " + argent(in.gainPotentielQuotas())
                    + " par mois de plus si elles passent au plan Pro."));
        }
        List<Long> encaisse = in.encaisseParMois();
        if (encaisse.size() >= 4) {
            double moyenne = Prevision.moyenneRecente(encaisse.subList(0, encaisse.size() - 1), 3);
            if (moyenne > 0) {
                constats.add(new Constat(INFO, "Encaissé en moyenne sur les trois derniers mois complets : "
                        + argent(Math.round(moyenne)) + " par mois."));
            }
        }
        return new Analyste("Revenus", "Prévoit le revenu mensuel", verdict, constats,
                fiabiliteParEffectif(payants.size()),
                "Revenu actuel, moins la perte probable, plus les inscriptions prévues converties au taux observé.");
    }

    // ─── Fidélisation ─────────────────────────────────────────────────────────

    private List<RisqueDepart> risques(List<Etablissement> enService, Entrees in, Map<Long, long[]> activiteAvant) {
        List<RisqueDepart> risques = new ArrayList<>();
        for (Etablissement e : enService) {
            long[] a = in.activite30j().getOrDefault(e.getId(), new long[] {0, 0});
            long[] avant = activiteAvant.getOrDefault(e.getId(), new long[] {0, 0});
            LocalDateTime dernier = in.dernierEmargement().get(e.getId());
            Long joursSansEmargement = dernier == null ? null
                    : ChronoUnit.DAYS.between(dernier.toLocalDate(), in.jour());
            long anciennete = e.getDateCreation() == null ? 0
                    : ChronoUnit.DAYS.between(e.getDateCreation().toLocalDate(), in.jour());
            Long joursAvantEcheance = abonnementService.joursAvantExpiration(e);

            int score = scoreRisque(joursSansEmargement, anciennete, a[0], a[1], avant[1], joursAvantEcheance);
            String raison = raisonRisque(joursSansEmargement, anciennete, a[0], a[1], avant[1], joursAvantEcheance);
            risques.add(new RisqueDepart(e, score, niveauRisque(score), raison, planService.prixMensuel(e.getPlan())));
        }
        risques.sort(Comparator.comparingInt(RisqueDepart::score).reversed()
                .thenComparing(r -> r.e().getNom(), String.CASE_INSENSITIVE_ORDER));
        return risques;
    }

    /**
     * Risque de départ sur 100. Quatre signaux s'additionnent : le silence (jours sans émargement,
     * ou école qui n'a jamais commencé), la baisse d'activité d'un mois sur l'autre, un taux
     * d'émargement faible, et une échéance proche ou dépassée.
     */
    static int scoreRisque(Long joursSansEmargement, long anciennete, long seances30j, long emargees30j,
                           long emargeesAvant, Long joursAvantEcheance) {
        int score;
        if (joursSansEmargement == null) {
            score = anciennete > 14 ? 60 : 30;
        } else if (joursSansEmargement <= 3) {
            score = 0;
        } else if (joursSansEmargement <= 7) {
            score = 10;
        } else if (joursSansEmargement <= 14) {
            score = 25;
        } else if (joursSansEmargement <= 30) {
            score = 45;
        } else {
            score = 60;
        }
        if (emargeesAvant >= 5 && emargees30j < emargeesAvant) {
            score += (int) Math.round(25.0 * (emargeesAvant - emargees30j) / emargeesAvant);
        }
        if (seances30j >= 5 && emargees30j * 2 < seances30j) {
            score += 10;
        }
        if (joursAvantEcheance != null) {
            if (joursAvantEcheance < 0) score += 30;
            else if (joursAvantEcheance <= 14) score += 15;
        }
        return Math.min(100, score);
    }

    static String niveauRisque(int score) {
        return score >= RISQUE_ELEVE ? "élevé" : score >= RISQUE_MOYEN ? "moyen" : "faible";
    }

    /** Le signal le plus lourd, dit en clair. */
    static String raisonRisque(Long joursSansEmargement, long anciennete, long seances30j, long emargees30j,
                               long emargeesAvant, Long joursAvantEcheance) {
        if (joursAvantEcheance != null && joursAvantEcheance < 0) return "abonnement expiré";
        if (joursSansEmargement == null) {
            return anciennete > 14 ? "inscrit depuis " + anciennete + " jours, jamais utilisé" : "pas encore démarré";
        }
        if (joursSansEmargement > 14) return "aucun émargement depuis " + joursSansEmargement + " jours";
        if (emargeesAvant >= 5 && emargees30j * 2 < emargeesAvant) {
            return "activité en baisse de " + Math.round(100.0 * (emargeesAvant - emargees30j) / emargeesAvant)
                    + " % sur un mois";
        }
        if (joursAvantEcheance != null && joursAvantEcheance <= 14) {
            return "abonnement à renouveler dans " + joursAvantEcheance + " jour(s)";
        }
        if (seances30j >= 5 && emargees30j * 2 < seances30j) return "moins d'une séance sur deux émargée";
        if (joursSansEmargement > 3) return "aucun émargement depuis " + joursSansEmargement + " jours";
        return "usage régulier";
    }

    private Analyste fidelisation(List<Etablissement> enService, List<RisqueDepart> risques) {
        List<RisqueDepart> eleves = risques.stream().filter(r -> r.score() >= RISQUE_ELEVE).toList();
        long moyens = risques.stream().filter(r -> r.score() >= RISQUE_MOYEN && r.score() < RISQUE_ELEVE).count();
        long expose = eleves.stream().mapToLong(RisqueDepart::montantMensuel).sum();

        List<Constat> constats = new ArrayList<>();
        String verdict;
        if (enService.isEmpty()) {
            verdict = "Aucun établissement en service : rien à surveiller.";
        } else if (eleves.isEmpty()) {
            verdict = "Aucun établissement à risque élevé de départ.";
        } else {
            verdict = (eleves.size() == 1 ? "1 établissement risque de partir"
                    : eleves.size() + " établissements risquent de partir")
                    + (expose > 0 ? ", " + argent(expose) + " par mois exposés." : ".");
        }
        risques.stream().filter(r -> r.score() >= RISQUE_MOYEN).limit(3).forEach(r ->
                constats.add(new Constat(r.score() >= RISQUE_ELEVE ? ALERTE : ATTENTION,
                        r.e().getNomEffectif() + " : risque " + r.score() + " sur 100, " + r.raison() + ".")));
        if (moyens > 0 && !eleves.isEmpty()) {
            constats.add(new Constat(INFO, moyens + " autre(s) établissement(s) à risque moyen, à surveiller."));
        }
        long reguliers = risques.stream().filter(r -> r.score() < RISQUE_MOYEN).count();
        if (!enService.isEmpty()) {
            constats.add(new Constat(BON, reguliers + " établissement(s) sur " + enService.size()
                    + " sans signal de départ."));
        }
        return new Analyste("Fidélisation", "Repère les départs probables", verdict, constats,
                fiabiliteParEffectif(enService.size()),
                "Note sur 100 par établissement : silence, baisse d'activité, taux d'émargement, échéance.");
    }

    // ─── Usage ────────────────────────────────────────────────────────────────

    private Analyste usage(Entrees in, Map<Long, long[]> activiteAvant, LocalDate jour) {
        long prevues = in.activite30j().values().stream().mapToLong(v -> v[0]).sum();
        long emargees = in.activite30j().values().stream().mapToLong(v -> v[1]).sum();
        long prevuesAvant = activiteAvant.values().stream().mapToLong(v -> v[0]).sum();
        long emargeesAvant = activiteAvant.values().stream().mapToLong(v -> v[1]).sum();
        long taux = PilotagePlateformeService.pourcentage(emargees, prevues);
        long tauxAvant = PilotagePlateformeService.pourcentage(emargeesAvant, prevuesAvant);

        List<Constat> constats = new ArrayList<>();
        String verdict;
        if (prevues == 0) {
            verdict = "Aucune séance prévue sur les 30 derniers jours.";
        } else {
            long ecart = taux - tauxAvant;
            verdict = taux + " % des séances émargées sur 30 jours"
                    + (prevuesAvant == 0 ? "." : ecart == 0 ? ", comme le mois précédent."
                    : ", " + Math.abs(ecart) + " point(s) de " + (ecart > 0 ? "plus" : "moins") + " que le mois précédent.");
            constats.add(new Constat(taux >= 80 ? BON : taux >= 50 ? ATTENTION : ALERTE,
                    emargees + " séance(s) émargée(s) sur " + prevues + " prévue(s), tous établissements confondus."));

            Map<DayOfWeek, long[]> parJourSemaine = new EnumMap<>(DayOfWeek.class);
            pilotage.activiteParJour(jour.minusDays(27), jour).forEach((d, v) -> {
                long[] cumul = parJourSemaine.computeIfAbsent(d.getDayOfWeek(), k -> new long[2]);
                cumul[0] += v[0];
                cumul[1] += v[1];
            });
            parJourSemaine.entrySet().stream().max(Comparator.comparingLong(en -> en.getValue()[0]))
                    .filter(en -> en.getValue()[0] > 0)
                    .ifPresent(en -> constats.add(new Constat(INFO, "Jour le plus chargé : le " + nomJour(en.getKey())
                            + ", avec " + en.getValue()[0] + " séance(s) en quatre semaines.")));
            parJourSemaine.entrySet().stream().filter(en -> en.getValue()[0] >= 5)
                    .min(Comparator.comparingLong(en -> PilotagePlateformeService.pourcentage(en.getValue()[1], en.getValue()[0])))
                    .filter(en -> PilotagePlateformeService.pourcentage(en.getValue()[1], en.getValue()[0]) < taux)
                    .ifPresent(en -> constats.add(new Constat(ATTENTION, "Jour le moins bien émargé : le "
                            + nomJour(en.getKey()) + ", à "
                            + PilotagePlateformeService.pourcentage(en.getValue()[1], en.getValue()[0]) + " %.")));
        }

        LocalDateTime debut = jour.minusDays(29).atStartOfDay();
        LocalDateTime fin = jour.plusDays(1).atStartOfDay();
        long emargements = emargementRepository.countByDateHeureBetween(debut, fin);
        if (emargements > 0) {
            long retard = emargementRepository.countByEnRetardTrueAndDateHeureBetween(debut, fin);
            long horsLigne = emargementRepository.countByHorsLigneTrueAndDateHeureBetween(debut, fin);
            constats.add(new Constat(INFO, PilotagePlateformeService.pourcentage(retard, emargements)
                    + " % des émargements faits en retard, "
                    + PilotagePlateformeService.pourcentage(horsLigne, emargements) + " % hors ligne."));
        }
        long inscrits = in.enseignants().values().stream().mapToLong(Long::longValue).sum();
        if (inscrits > 0) {
            long actifs = emargementRepository.enseignantsActifsDepuis(debut);
            constats.add(new Constat(INFO, "Enseignants ayant émargé au moins une fois en 30 jours : "
                    + actifs + " sur " + inscrits + "."));
        }
        return new Analyste("Usage", "Mesure l'utilisation réelle", verdict, constats,
                prevues >= 100 ? "bonne" : prevues >= 20 ? "moyenne" : "faible",
                "Séances prévues et émargées des 30 derniers jours, comparées aux 30 jours d'avant.");
    }

    // ─── Marché ───────────────────────────────────────────────────────────────

    private Analyste marche(Entrees in, List<Etablissement> enService, List<Etablissement> payants, double rythme) {
        long clients = in.clients().stream().filter(e -> e.getStatut() == StatutEtablissement.VALIDE).count();
        List<Constat> constats = new ArrayList<>();
        String verdict;
        if (marcheCible > 0) {
            verdict = unDecimal(clients * 100.0 / marcheCible) + " % du marché visé : " + clients
                    + " établissement(s) sur " + marcheCible + ".";
            double dansUnAn = Math.min(100, (clients + rythme * 12) * 100.0 / marcheCible);
            constats.add(new Constat(INFO, "Au rythme actuel des inscriptions, " + unDecimal(dansUnAn)
                    + " % du marché dans douze mois."));
        } else {
            verdict = "Taille du marché à renseigner pour calculer votre part.";
            constats.add(new Constat(ATTENTION, "Indiquez le nombre d'établissements visés (variable "
                    + "MARCHE_ETABLISSEMENTS_CIBLES) : SIGEP ne peut pas le deviner."));
        }

        // Quel type d'établissement adopte le mieux SIGEP : compte, part payante, taux d'émargement.
        Map<String, long[]> segments = new LinkedHashMap<>();
        Set<Long> idsPayants = new java.util.HashSet<>();
        payants.forEach(e -> idsPayants.add(e.getId()));
        for (Etablissement e : enService) {
            long[] s = segments.computeIfAbsent(nomSegment(e.getTypeEtablissement()), k -> new long[4]);
            long[] a = in.activite30j().getOrDefault(e.getId(), new long[] {0, 0});
            s[0]++;
            if (idsPayants.contains(e.getId())) s[1]++;
            s[2] += a[0];
            s[3] += a[1];
        }
        segments.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]))
                .limit(3)
                .forEach(en -> {
                    long[] s = en.getValue();
                    constats.add(new Constat(INFO, en.getKey() + " : " + s[0] + " établissement(s), dont " + s[1]
                            + " payant(s)" + (s[2] > 0 ? ", " + PilotagePlateformeService.pourcentage(s[3], s[2])
                            + " % de séances émargées." : ".")));
                });
        constats.add(new Constat(INFO, "Lecture tirée des seules données de SIGEP : aucune source extérieure "
                + "n'est interrogée."));
        return new Analyste("Marché", "Situe SIGEP sur son marché", verdict, constats,
                marcheCible > 0 ? fiabiliteParEffectif(enService.size()) : "faible",
                "Clients validés rapportés à la taille du marché renseignée, et répartition par type d'établissement.");
    }

    // ─── Mise en forme ────────────────────────────────────────────────────────

    private String argent(long montant) {
        return String.format(Locale.FRENCH, "%,d", montant) + " " + planService.symbole();
    }

    private static String unDecimal(double v) {
        String s = String.format(Locale.FRENCH, "%.1f", v);
        return s.endsWith(",0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String fiabiliteParEffectif(int effectif) {
        return effectif >= 20 ? "bonne" : effectif >= 5 ? "moyenne" : "faible";
    }

    private static String nomJour(DayOfWeek j) {
        return j.getDisplayName(TextStyle.FULL, Locale.FRENCH);
    }

    private static String nomSegment(String type) {
        if (type == null || type.isBlank()) return "Autres";
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "SUPERIEUR" -> "Supérieur";
            case "SECONDAIRE" -> "Secondaire";
            case "PRIMAIRE" -> "Primaire";
            case "PROFESSIONNEL" -> "Professionnel";
            default -> type.substring(0, 1).toUpperCase(Locale.ROOT) + type.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    static String etiquetteCourte(YearMonth mois) {
        String lbl = mois.getMonth().getDisplayName(TextStyle.SHORT, Locale.FRENCH).replace(".", "");
        return lbl.isEmpty() ? lbl : Character.toUpperCase(lbl.charAt(0)) + lbl.substring(1);
    }
}
