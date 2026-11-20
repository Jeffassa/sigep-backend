package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /** Premier compte d'un établissement (= l'admin créé à l'inscription). Vue plateforme. */
    Optional<User> findFirstByEtablissementIdOrderByIdAsc(Long etablissementId);

    /** Vue de la plateforme : l'e-mail du premier compte de chaque établissement, en une requête. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT u.etablissement.id, u.email FROM User u WHERE u.id IN "
            + "(SELECT MIN(x.id) FROM User x WHERE x.etablissement IS NOT NULL GROUP BY x.etablissement.id)")
    java.util.List<Object[]> premierCompteParEtablissement();

    /** Tous les comptes d'un établissement (fiche plateforme : liste des administrateurs). */
    java.util.List<User> findByEtablissementIdOrderByIdAsc(Long etablissementId);
}
