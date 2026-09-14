package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.Salle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SalleRepository extends JpaRepository<Salle, Long> {
    Optional<Salle> findByLibelleIgnoreCase(String libelle);
    boolean existsByLibelleIgnoreCase(String libelle);

    /** Combien de salles dépendent de ce campus : sert à refuser une suppression qui les orphelinerait. */
    long countByCampusId(Long campusId);
}
