package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.StatutEnseignant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EnseignantRepository extends JpaRepository<Enseignant, Long> {
    Optional<Enseignant> findByMatricule(String matricule);
    Optional<Enseignant> findByUserId(Long userId);
    boolean existsByMatricule(String matricule);

    @Query("SELECT DISTINCT e.departement FROM Enseignant e WHERE e.departement IS NOT NULL ORDER BY e.departement")
    List<String> findDistinctDepartements();

    Page<Enseignant> findByNomContainingIgnoreCaseOrPrenomContainingIgnoreCase(
            String nom, String prenom, Pageable pageable);

    long countByStatut(StatutEnseignant statut);

    /** Nombre d'enseignants d'un établissement (pour le quota de plan). */
    long countByEtablissementId(Long etablissementId);

    List<Enseignant> findByStatutOrderByNomAsc(StatutEnseignant statut);

    @Query(value = "SELECT * FROM enseignants e WHERE " +
                   "(CAST(:search AS varchar) IS NULL " +
                   " OR LOWER(e.nom) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%')) " +
                   " OR LOWER(e.prenom) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%')) " +
                   " OR LOWER(e.matricule) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%'))) " +
                   "AND (CAST(:departement AS varchar) IS NULL OR e.departement = CAST(:departement AS varchar)) " +
                   // ISOLATION : requête native -> le filtre Hibernate ne s'applique pas, on filtre ici.
                   "AND (CAST(:tenantId AS bigint) IS NULL OR e.etablissement_id = :tenantId)",
           countQuery = "SELECT COUNT(*) FROM enseignants e WHERE " +
                        "(CAST(:search AS varchar) IS NULL " +
                        " OR LOWER(e.nom) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%')) " +
                        " OR LOWER(e.prenom) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%')) " +
                        " OR LOWER(e.matricule) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%'))) " +
                        "AND (CAST(:departement AS varchar) IS NULL OR e.departement = CAST(:departement AS varchar)) " +
                        "AND (CAST(:tenantId AS bigint) IS NULL OR e.etablissement_id = :tenantId)",
           nativeQuery = true)
    Page<Enseignant> searchEnseignants(@Param("search") String search,
                                        @Param("departement") String departement,
                                        @Param("tenantId") Long tenantId,
                                        Pageable pageable);

    @Query(value = "SELECT * FROM enseignants e WHERE " +
                   "(CAST(:search AS varchar) IS NULL " +
                   " OR LOWER(e.nom) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%')) " +
                   " OR LOWER(e.prenom) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%')) " +
                   " OR LOWER(e.matricule) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%'))) " +
                   "AND (CAST(:departement AS varchar) IS NULL OR e.departement = CAST(:departement AS varchar)) " +
                   "AND e.statut = CAST(:statut AS varchar) " +
                   // ISOLATION : requête native -> le filtre Hibernate ne s'applique pas, on filtre ici.
                   "AND (CAST(:tenantId AS bigint) IS NULL OR e.etablissement_id = :tenantId)",
           countQuery = "SELECT COUNT(*) FROM enseignants e WHERE " +
                        "(CAST(:search AS varchar) IS NULL " +
                        " OR LOWER(e.nom) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%')) " +
                        " OR LOWER(e.prenom) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%')) " +
                        " OR LOWER(e.matricule) LIKE LOWER(CONCAT('%', CAST(:search AS varchar), '%'))) " +
                        "AND (CAST(:departement AS varchar) IS NULL OR e.departement = CAST(:departement AS varchar)) " +
                        "AND e.statut = CAST(:statut AS varchar) " +
                        "AND (CAST(:tenantId AS bigint) IS NULL OR e.etablissement_id = :tenantId)",
           nativeQuery = true)
    Page<Enseignant> searchEnseignantsByStatut(@Param("search") String search,
                                               @Param("departement") String departement,
                                               @Param("statut") String statut,
                                               @Param("tenantId") Long tenantId,
                                               Pageable pageable);
}
