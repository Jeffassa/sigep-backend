package ci.esatic.sigep.annuaire;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EtablissementOfficielRepository extends JpaRepository<EtablissementOfficiel, Long> {

    @Query("select o from EtablissementOfficiel o where o.nomRecherche like concat('%', :mot, '%')")
    List<EtablissementOfficiel> contenant(@Param("mot") String mot, Pageable page);

    List<EtablissementOfficiel> findByNomCle(String nomCle);

    long countBySource(SourceOfficielle source);
}
