package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.Faculte;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FaculteRepository extends JpaRepository<Faculte, Long> {
    boolean existsByCodeIgnoreCase(String code);
    boolean existsByNomIgnoreCase(String nom);
    List<Faculte> findAllByOrderByNomAsc();
}
