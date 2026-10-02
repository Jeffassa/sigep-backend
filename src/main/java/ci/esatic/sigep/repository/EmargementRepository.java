package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.Emargement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EmargementRepository extends JpaRepository<Emargement, Long> {
    Optional<Emargement> findBySeanceId(Long seanceId);
    boolean existsBySeanceId(Long seanceId);

    List<Emargement> findByEnseignantId(Long enseignantId);

    /** Sert à refuser la suppression d'un enseignant dont la présence a déjà été enregistrée. */
    long countByEnseignantId(Long enseignantId);

    @Query("SELECT e FROM Emargement e WHERE e.enseignant.id = :enseignantId " +
           "AND e.dateHeure BETWEEN :debut AND :fin")
    List<Emargement> findByEnseignantIdAndPeriode(@Param("enseignantId") Long enseignantId,
                                                   @Param("debut") LocalDateTime debut,
                                                   @Param("fin") LocalDateTime fin);

    long countByDateHeureBetween(LocalDateTime debut, LocalDateTime fin);

    List<Emargement> findTop5ByOrderByDateHeureDesc();

    // Statistiques admin : émargements faits APRÈS la fin de la séance (rattrapage d'oubli)
    @Query("SELECT COUNT(e) FROM Emargement e WHERE e.enRetard = true " +
           "AND e.seance.date BETWEEN :debut AND :fin")
    long countEnRetardByPeriode(@Param("debut") LocalDate debut, @Param("fin") LocalDate fin);

    // Statistiques admin : émargements enregistrés HORS-LIGNE (sans QR de salle)
    @Query("SELECT COUNT(e) FROM Emargement e WHERE e.horsLigne = true " +
           "AND e.seance.date BETWEEN :debut AND :fin")
    long countHorsLigneByPeriode(@Param("debut") LocalDate debut, @Param("fin") LocalDate fin);

    // C2 — file d'attente admin : hors-ligne EN ATTENTE de validation (présence non confirmée).
    @Query("SELECT e FROM Emargement e WHERE e.horsLigne = true AND e.valide = false ORDER BY e.dateHeure DESC")
    List<Emargement> findEmargementsHorsLigneEnAttente();

    // Badge d'alertes : nb de hors-ligne en attente (actionnable, contrairement au cumul total).
    long countByHorsLigneTrueAndValideFalse();

    /**
     * Export paie : retards et hors-ligne cumulés par enseignant sur une plage de dates de séance.
     *
     * <p>Un agrégat plutôt qu'une requête par enseignant. L'export de paie est justement l'écran
     * où l'établissement est le plus gros : une boucle y coûterait une requête par ligne du
     * fichier.
     *
     * @return des lignes {@code [enseignantId, nbRetards, nbHorsLigne]}
     */
    @Query("SELECT e.enseignant.id, "
         + "SUM(CASE WHEN e.enRetard = true THEN 1 ELSE 0 END), "
         + "SUM(CASE WHEN e.horsLigne = true THEN 1 ELSE 0 END) "
         + "FROM Emargement e WHERE e.seance.date BETWEEN :debut AND :fin "
         + "GROUP BY e.enseignant.id")
    List<Object[]> signauxParEnseignant(@Param("debut") LocalDate debut,
                                        @Param("fin") LocalDate fin);

    // C2 — plafond : nb de hors-ligne d'un enseignant sur une plage de dates de séance.
    @Query("SELECT COUNT(e) FROM Emargement e WHERE e.horsLigne = true " +
           "AND e.enseignant.id = :enseignantId AND e.seance.date BETWEEN :debut AND :fin")
    long countHorsLigneByEnseignantEtPeriode(@Param("enseignantId") Long enseignantId,
                                             @Param("debut") LocalDate debut,
                                             @Param("fin") LocalDate fin);
}
