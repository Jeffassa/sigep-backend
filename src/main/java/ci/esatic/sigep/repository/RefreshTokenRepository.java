package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.RefreshToken;
import ci.esatic.sigep.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from RefreshToken r where r.user = :user")
    void deleteByUser(@Param("user") User user);

    @Modifying
    @Query("delete from RefreshToken r where r.expiryDate < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
