package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.PeriodeCalendrier;
import ci.esatic.sigep.entity.TypePeriode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PeriodeCalendrierRepository extends JpaRepository<PeriodeCalendrier, Long> {

    /** Restreintes à l'établissement courant par le filtre Hibernate. */
    List<PeriodeCalendrier> findAllByOrderByDebutAsc();

    List<PeriodeCalendrier> findByTypeOrderByDebutAsc(TypePeriode type);
}
