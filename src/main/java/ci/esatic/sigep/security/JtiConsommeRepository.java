package ci.esatic.sigep.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface JtiConsommeRepository extends JpaRepository<JtiConsomme, String> {

    /** Purge des identifiants dont la rétention est dépassée. */
    @Modifying
    @Query("DELETE FROM JtiConsomme j WHERE j.expireLe < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
