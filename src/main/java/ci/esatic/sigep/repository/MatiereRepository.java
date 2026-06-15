package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.Matiere;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MatiereRepository extends JpaRepository<Matiere, Long> {
    Optional<Matiere> findByCode(String code);
    boolean existsByCode(String code);
}
