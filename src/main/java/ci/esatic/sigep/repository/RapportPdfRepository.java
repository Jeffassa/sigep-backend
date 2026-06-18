package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.RapportPdf;
import ci.esatic.sigep.entity.TypeRapport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface RapportPdfRepository extends JpaRepository<RapportPdf, Long> {

    List<RapportPdf> findByEnseignantIdOrderByDateGenerationDesc(Long enseignantId);

    List<RapportPdf> findAllByOrderByDateGenerationDesc();

    @Query("SELECT r FROM RapportPdf r WHERE r.periodeDebut >= :debut AND r.periodeFin <= :fin ORDER BY r.dateGeneration DESC")
    List<RapportPdf> findByPeriode(@Param("debut") LocalDate debut, @Param("fin") LocalDate fin);

    // Recherche filtrée : par enseignant (optionnel) et chevauchement de période (optionnel)
    @Query("SELECT r FROM RapportPdf r WHERE " +
           "(:enseignantId IS NULL OR r.enseignant.id = :enseignantId) AND " +
           "(:debut IS NULL OR r.periodeFin >= :debut) AND " +
           "(:fin IS NULL OR r.periodeDebut <= :fin) " +
           "ORDER BY r.dateGeneration DESC")
    List<RapportPdf> findFiltres(@Param("enseignantId") Long enseignantId,
                                 @Param("debut") LocalDate debut,
                                 @Param("fin") LocalDate fin);

    boolean existsByEnseignantIdAndPeriodeDebutAndType(Long enseignantId, LocalDate periodeDebut, TypeRapport type);
}
