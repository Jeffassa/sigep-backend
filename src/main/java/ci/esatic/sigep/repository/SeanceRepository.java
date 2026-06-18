package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.Seance;
import ci.esatic.sigep.entity.StatutSeance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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

    // Alertes admin : séances passées dont l'émargement est manquant (statut != EMARGE)
    @Query("SELECT s FROM Seance s WHERE s.date < :date AND s.statut <> 'EMARGE' " +
           "ORDER BY s.date DESC, s.heureDebut DESC")
    List<Seance> findSeancesNonEmargees(@Param("date") LocalDate date);

    @Query("SELECT COUNT(s) FROM Seance s WHERE s.date < :date AND s.statut <> 'EMARGE'")
    long countSeancesNonEmargees(@Param("date") LocalDate date);

    @Query("SELECT s FROM Seance s WHERE s.date BETWEEN :debut AND :fin " +
           "ORDER BY s.date ASC, s.heureDebut ASC")
    List<Seance> findAllByDateBetweenOrdered(@Param("debut") LocalDate debut,
                                              @Param("fin") LocalDate fin);
}
