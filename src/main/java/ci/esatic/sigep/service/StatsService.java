package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.response.StatsResponse;
import ci.esatic.sigep.entity.Emargement;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.StatutDemande;
import ci.esatic.sigep.entity.StatutSeance;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.DemandeRattrapageRepository;
import ci.esatic.sigep.repository.EmargementRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatsService {

    private final EnseignantRepository enseignantRepository;
    private final SeanceRepository seanceRepository;
    private final EmargementRepository emargementRepository;
    private final DemandeRattrapageRepository rattrapageRepository;

    /** Forfait horaire par séance émargée (les séances font 2 h ici). */
    private static final double HEURES_PAR_SEANCE = 2.0;
    private static final DateTimeFormatter SEM_FMT = DateTimeFormatter.ofPattern("dd/MM");
    private static final String[] JOURS = {"Lun", "Mar", "Mer", "Jeu", "Ven", "Sam"};
    private static final String[] CRENEAUX = {"08h", "10h", "12h", "14h", "16h", "18h"};

    public StatsResponse getStatsEnseignant(Long userId) {
        Enseignant ens = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "userId", userId));
        Long id = ens.getId();

        long total = seanceRepository.countByEnseignantId(id);

        List<Emargement> emargements = emargementRepository.findByEnseignantId(id);
        long emargees = emargements.size();
        double heures = emargements.stream()
                .mapToDouble(e -> Duration.between(
                        e.getSeance().getHeureDebut(), e.getSeance().getHeureFin()).toMinutes() / 60.0)
                .sum();
        long enRetard = emargements.stream().filter(Emargement::isEnRetard).count();
        double taux = total > 0 ? Math.round(emargees * 1000.0 / total) / 10.0 : 0.0;

        LocalDate today = LocalDate.now();
        LocalDate lundi = today.with(DayOfWeek.MONDAY);
        LocalDate dimanche = lundi.plusDays(6);
        long seancesSemaine = seanceRepository.findByEnseignantIdAndDateBetween(id, lundi, dimanche).size();
        long emargeesSemaine = seanceRepository.countEmargeesParEnseignant(id, lundi, dimanche);

        return StatsResponse.builder()
                .seancesTotal(total)
                .seancesEmargees(emargees)
                .seancesNonEmargees(Math.max(0, total - emargees))
                .tauxEmargement(taux)
                .heuresEffectuees(Math.round(heures * 10) / 10.0)
                .emargementsEnRetard(enRetard)
                .seancesSemaine(seancesSemaine)
                .emargeesSemaine(emargeesSemaine)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Statistiques globales (page admin /admin/statistiques)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> computeStatistiques(LocalDate debut, LocalDate fin) {
        Map<String, Object> m = new HashMap<>();

        // Période précédente de même longueur (pour la comparaison)
        long nbJours = ChronoUnit.DAYS.between(debut, fin) + 1;
        LocalDate debutPrec = debut.minusDays(nbJours);
        LocalDate finPrec = debut.minusDays(1);

        long seances = seanceRepository.countAllByDateBetween(debut, fin);
        long emargees = seanceRepository.countAllEmargesByDateBetween(debut, fin);
        double taux = pct(emargees, seances);
        double tauxPrec = pct(seanceRepository.countAllEmargesByDateBetween(debutPrec, finPrec),
                seanceRepository.countAllByDateBetween(debutPrec, finPrec));
        double delta = arrondi(taux - tauxPrec);

        m.put("debut", debut);
        m.put("fin", fin);
        m.put("seances", seances);
        m.put("emargees", emargees);
        m.put("taux", taux);
        m.put("tauxDelta", delta);
        m.put("heures", arrondi(emargees * HEURES_PAR_SEANCE));
        m.put("profsActifs", enseignantRepository.count());
        m.put("rattrapages", rattrapageRepository.countByStatut(StatutDemande.EN_ATTENTE));

        // Qualité de l'émargement : à l'heure / en retard / oublis
        long enRetard = emargementRepository.countEnRetardByPeriode(debut, fin);
        m.put("emargesEnRetard", enRetard);
        m.put("emargesALheure", Math.max(0, emargees - enRetard));

        // Projection (date, heure début, statut) -> heatmap jour×créneau + oublis
        double[][] hmTot = new double[6][6];
        double[][] hmEmarg = new double[6][6];
        long oublis = 0;
        LocalDate today = LocalDate.now();
        for (Object[] r : seanceRepository.projectionPeriode(debut, fin)) {
            LocalDate d = (LocalDate) r[0];
            LocalTime h = (LocalTime) r[1];
            StatutSeance st = (StatutSeance) r[2];
            if (d.isBefore(today) && st != StatutSeance.EMARGE) oublis++;
            int dow = d.getDayOfWeek().getValue();          // 1=Lun .. 7=Dim
            if (dow >= 1 && dow <= 6 && h != null) {
                int ji = dow - 1;
                int ci = Math.min(5, Math.max(0, (h.getHour() - 8) / 2));
                hmTot[ji][ci]++;
                if (st == StatutSeance.EMARGE) hmEmarg[ji][ci]++;
            }
        }
        m.put("oublis", oublis);
        m.put("creneaux", Arrays.asList(CRENEAUX));
        m.put("heatmap", buildHeatmap(hmTot, hmEmarg));

        // Répartitions
        List<Map<String, Object>> parEnseignant = parEnseignant(debut, fin);
        List<Map<String, Object>> parClasse = mapAgg(seanceRepository.tauxParClasse(debut, fin));
        List<Map<String, Object>> parMatiere = mapAgg(seanceRepository.tauxParMatiere(debut, fin));
        m.put("parEnseignant", parEnseignant);
        m.put("parClasse", parClasse);
        m.put("parMatiere", parMatiere);

        // Tendance (8 dernières semaines)
        m.put("tendance", tendance(8));

        // Analyse / recommandations (règles)
        m.put("recommandations", recommandations(seances, taux, delta, oublis, parEnseignant, parClasse));
        return m;
    }

    private List<Map<String, Object>> parEnseignant(LocalDate debut, LocalDate fin) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Enseignant e : enseignantRepository.findAll()) {
            long tot = seanceRepository.countByEnseignantIdAndPeriode(e.getId(), debut, fin);
            if (tot == 0) continue;
            long em = seanceRepository.countEmargeesParEnseignant(e.getId(), debut, fin);
            double tx = pct(em, tot);
            Map<String, Object> r = new HashMap<>();
            r.put("nom", e.getPrenom() + " " + e.getNom());
            r.put("matricule", e.getMatricule());
            r.put("total", tot);
            r.put("emargees", em);
            r.put("taux", tx);
            r.put("heures", arrondi(em * HEURES_PAR_SEANCE));
            r.put("niveau", niveau(tx));
            out.add(r);
        }
        out.sort((a, b) -> Double.compare((double) b.get("taux"), (double) a.get("taux")));
        return out;
    }

    private List<Map<String, Object>> mapAgg(List<Object[]> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            long tot = ((Number) r[1]).longValue();
            long em = r[2] == null ? 0 : ((Number) r[2]).longValue();
            Map<String, Object> mm = new HashMap<>();
            mm.put("libelle", r[0] == null ? "—" : r[0]);
            mm.put("total", tot);
            mm.put("emargees", em);
            mm.put("taux", pct(em, tot));
            out.add(mm);
        }
        out.sort((a, b) -> Double.compare((double) b.get("taux"), (double) a.get("taux")));
        return out;
    }

    private List<Map<String, Object>> buildHeatmap(double[][] tot, double[][] emarg) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int j = 0; j < 6; j++) {
            List<Map<String, Object>> cells = new ArrayList<>();
            for (int c = 0; c < 6; c++) {
                long t = (long) tot[j][c];
                double tx = t > 0 ? arrondi(emarg[j][c] * 100.0 / t) : -1.0;
                Map<String, Object> cell = new HashMap<>();
                cell.put("total", t);
                cell.put("taux", tx);
                cell.put("vide", t == 0);
                cell.put("opacity", t > 0 ? (tx / 100.0 * 0.82 + 0.10) : 0.0); // intensité ∝ taux
                cell.put("dark", tx >= 55);                                    // texte clair sur fond foncé
                cells.add(cell);
            }
            Map<String, Object> row = new HashMap<>();
            row.put("jour", JOURS[j]);
            row.put("cells", cells);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> tendance(int nbSemaines) {
        LocalDate lundi = LocalDate.now().with(DayOfWeek.MONDAY);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = nbSemaines - 1; i >= 0; i--) {
            LocalDate l = lundi.minusWeeks(i);
            LocalDate dim = l.plusDays(6);
            long s = seanceRepository.countAllByDateBetween(l, dim);
            long e = seanceRepository.countAllEmargesByDateBetween(l, dim);
            Map<String, Object> mm = new HashMap<>();
            mm.put("label", l.format(SEM_FMT));
            mm.put("taux", pct(e, s));
            out.add(mm);
        }
        return out;
    }

    private List<Map<String, String>> recommandations(long seances, double taux, double delta, long oublis,
                                                       List<Map<String, Object>> parEnseignant,
                                                       List<Map<String, Object>> parClasse) {
        List<Map<String, String>> recs = new ArrayList<>();
        if (seances == 0) {
            recs.add(rec("info", "Aucune séance sur la période sélectionnée."));
            return recs;
        }
        if (delta <= -5) recs.add(rec("alerte", "Le taux d'émargement a baissé de " + Math.abs(delta)
                + " pts par rapport à la période précédente."));
        else if (delta >= 5) recs.add(rec("succes", "Le taux d'émargement progresse de +" + delta + " pts."));

        List<String> faibles = new ArrayList<>();
        for (Map<String, Object> e : parEnseignant) if ((double) e.get("taux") < 50) faibles.add((String) e.get("nom"));
        if (!faibles.isEmpty()) recs.add(rec("alerte", faibles.size() + " enseignant(s) sous 50 % à relancer : "
                + String.join(", ", faibles) + "."));

        if (oublis > 0) recs.add(rec("info", oublis + " séance(s) passée(s) non émargée(s) sur la période."));

        if (!parClasse.isEmpty()) {
            Map<String, Object> pire = parClasse.get(parClasse.size() - 1);
            if ((double) pire.get("taux") < 60 && (long) pire.get("total") > 0)
                recs.add(rec("alerte", "Classe la moins assidue : " + pire.get("libelle")
                        + " (" + pire.get("taux") + " %)."));
        }

        if (taux >= 90) recs.add(rec("succes", "Excellent taux global d'émargement (" + taux + " %)."));
        else if (taux < 50) recs.add(rec("alerte", "Taux global faible (" + taux + " %) : sensibiliser les enseignants."));

        if (recs.isEmpty()) recs.add(rec("info", "Aucune anomalie détectée sur la période."));
        return recs;
    }

    private Map<String, String> rec(String type, String texte) {
        Map<String, String> r = new HashMap<>();
        r.put("type", type);
        r.put("texte", texte);
        return r;
    }

    private String niveau(double taux) {
        if (taux >= 90) return "Excellent";
        if (taux >= 75) return "Bon";
        if (taux >= 50) return "Moyen";
        return "Faible";
    }

    private double pct(long part, long total) {
        return total > 0 ? Math.round(part * 1000.0 / total) / 10.0 : 0.0;
    }

    private double arrondi(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
