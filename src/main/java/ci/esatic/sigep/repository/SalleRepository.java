package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.Salle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SalleRepository extends JpaRepository<Salle, Long> {
    Optional<Salle> findByCode(String code);
    boolean existsByCode(String code);
}
