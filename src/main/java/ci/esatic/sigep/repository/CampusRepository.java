package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.Campus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CampusRepository extends JpaRepository<Campus, Long> {

    /** Les requêtes sont déjà restreintes à l'établissement courant par le filtre Hibernate. */
    List<Campus> findAllByOrderByNomAsc();

    Optional<Campus> findByNomIgnoreCase(String nom);

    boolean existsByNomIgnoreCase(String nom);
}
