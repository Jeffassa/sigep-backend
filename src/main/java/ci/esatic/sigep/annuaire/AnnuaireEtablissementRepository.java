package ci.esatic.sigep.annuaire;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AnnuaireEtablissementRepository extends JpaRepository<AnnuaireEtablissement, Long> {

    @Query("select a from AnnuaireEtablissement a where a.nomRecherche like concat('%', :mot, '%')"
            + " and (:pays is null or a.paysCode = :pays)")
    List<AnnuaireEtablissement> contenant(@Param("mot") String mot, @Param("pays") String pays, Pageable page);

    long countByPaysCode(String paysCode);
}
