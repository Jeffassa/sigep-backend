package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.ApplicationMobile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ApplicationMobileRepository extends JpaRepository<ApplicationMobile, Long> {

    Optional<ApplicationMobile> findFirstByOrderByDateDepotDescIdDesc();

    /** Un seul morceau à la fois : le fichier entier ne passe jamais en mémoire. */
    @Query("select m.donnees from ApplicationMobileMorceau m where m.applicationId = :id and m.numero = :numero")
    byte[] morceau(@Param("id") Long id, @Param("numero") int numero);

    @Modifying
    @Query("delete from ApplicationMobileMorceau m")
    void supprimerMorceaux();

    @Modifying
    @Query("delete from ApplicationMobile a")
    void supprimerVersions();
}
