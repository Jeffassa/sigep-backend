package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.Seance;
import ci.esatic.sigep.entity.StatutSeance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface SeanceRepository extends JpaRepository<Seance, Long> {

    List<Seance> findByEnseignantIdAndDateOrderByHeureDebutAsc(Long enseignantId, LocalDate date);

    @Query("SELECT s FROM Seance s WHERE s.enseignant.id = :enseignantId " +
           "AND s.date BETWEEN :debut AND :fin ORDER BY s.date ASC, s.heureDebut ASC")
    List<Seance> findByEnseignantIdAndDateBetween(@Param("enseignantId") Long enseignantId,
                                                   @Param("debut") LocalDate debut,
                                                   @Param("fin") LocalDate fin);

    List<Seance> findByEnseignantIdAndStatut(Long enseignantId, StatutSeance statut);

    long countByEnseignantId(Long enseignantId);

    @Query("SELECT COUNT(s) FROM Seance s WHERE s.enseignant.id = :enseignantId " +
           "AND s.date BETWEEN :debut AND :fin")
    long countByEnseignantIdAndPeriode(@Param("enseignantId") Long enseignantId,
                                       @Param("debut") LocalDate debut,
                                       @Param("fin") LocalDate fin);

    @Query("SELECT COUNT(s) FROM Seance s WHERE s.enseignant.id = :enseignantId " +
           "AND s.date BETWEEN :debut AND :fin AND s.statut = 'EMARGE'")
    long countEmargeesParEnseignant(@Param("enseignantId") Long enseignantId,
                                     @Param("debut") LocalDate debut,
                                     @Param("fin") LocalDate fin);

    // Requêtes globales (tous enseignants) — pour les statistiques admin
    @Query("SELECT COUNT(s) FROM Seance s WHERE s.date BETWEEN :debut AND :fin")
    long countAllByDateBetween(@Param("debut") LocalDate debut, @Param("fin") LocalDate fin);

    @Query("SELECT COUNT(s) FROM Seance s WHERE s.date BETWEEN :debut AND :fin AND s.statut = 'EMARGE'")
    long countAllEmargesByDateBetween(@Param("debut") LocalDate debut, @Param("fin") LocalDate fin);

    @Query("SELECT COUNT(s) FROM Seance s WHERE s.date = :date")
    long countAllByDate(@Param("date") LocalDate date);

    @Query("SELECT COUNT(s) FROM Seance s WHERE s.date = :date AND s.statut = 'EMARGE'")
    long countAllEmargesByDate(@Param("date") LocalDate date);

    List<Seance> findAllByDateOrderByHeureDebutAsc(LocalDate date);

    // Alertes admin : séances dont l'émargement est manquant (statut != EMARGE) ET déjà
    // terminées — jours précédents + créneaux d'aujourd'hui dont l'heure de fin est dépassée.
    // (aligné sur la relance e-mail qui couvre les oublis du jour même.)
    @Query("SELECT s FROM Seance s WHERE s.statut <> 'EMARGE' " +
           "AND (s.date < :date OR (s.date = :date AND s.heureFin < :heure)) " +
           "ORDER BY s.date DESC, s.heureDebut DESC")
    List<Seance> findSeancesNonEmargees(@Param("date") LocalDate date, @Param("heure") LocalTime heure);

    @Query("SELECT COUNT(s) FROM Seance s WHERE s.statut <> 'EMARGE' " +
           "AND (s.date < :date OR (s.date = :date AND s.heureFin < :heure))")
    long countSeancesNonEmargees(@Param("date") LocalDate date, @Param("heure") LocalTime heure);

    @Query("SELECT s FROM Seance s WHERE s.date BETWEEN :debut AND :fin " +
           "ORDER BY s.date ASC, s.heureDebut ASC")
    List<Seance> findAllByDateBetweenOrdered(@Param("debut") LocalDate debut,
                                              @Param("fin") LocalDate fin);

    // ── Agrégats pour la page Statistiques admin ──
    @Query("SELECT s.classe.libelle, COUNT(s), " +
           "SUM(CASE WHEN s.statut = 'EMARGE' THEN 1 ELSE 0 END) " +
           "FROM Seance s WHERE s.date BETWEEN :debut AND :fin " +
           "GROUP BY s.classe.libelle ORDER BY s.classe.libelle")
    List<Object[]> tauxParClasse(@Param("debut") LocalDate debut, @Param("fin") LocalDate fin);

    @Query("SELECT s.matiere.libelle, COUNT(s), " +
           "SUM(CASE WHEN s.statut = 'EMARGE' THEN 1 ELSE 0 END) " +
           "FROM Seance s WHERE s.date BETWEEN :debut AND :fin " +
           "GROUP BY s.matiere.libelle ORDER BY s.matiere.libelle")
    List<Object[]> tauxParMatiere(@Param("debut") LocalDate debut, @Param("fin") LocalDate fin);

    // Agrégat PAR ENSEIGNANT en UNE requête (remplace la boucle N+1 : findAll + 2 counts/enseignant).
    // Ne renvoie que les enseignants ayant au moins une séance sur la période.
    @Query("SELECT s.enseignant.id, s.enseignant.prenom, s.enseignant.nom, s.enseignant.matricule, " +
           "COUNT(s), SUM(CASE WHEN s.statut = 'EMARGE' THEN 1 ELSE 0 END) " +
           "FROM Seance s WHERE s.date BETWEEN :debut AND :fin " +
           "GROUP BY s.enseignant.id, s.enseignant.prenom, s.enseignant.nom, s.enseignant.matricule")
    List<Object[]> tauxParEnseignant(@Param("debut") LocalDate debut, @Param("fin") LocalDate fin);

    // Projection légère (date, heure de début, statut) pour la heatmap et les oublis
    @Query("SELECT s.date, s.heureDebut, s.statut FROM Seance s WHERE s.date BETWEEN :debut AND :fin")
    List<Object[]> projectionPeriode(@Param("debut") LocalDate debut, @Param("fin") LocalDate fin);
}
