package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    /**
     * Comptes portant cette adresse, quelle que soit la casse.
     *
     * <p>Rend une LISTE, et c'est délibéré. La contrainte d'unicité de la colonne distingue les
     * majuscules : « Admin@ecole.ci » et « admin@ecole.ci » peuvent donc coexister. Un
     * {@code Optional} échouerait brutalement sur ce cas, là où l'appelant — la connexion SSO —
     * doit pouvoir constater l'ambiguïté et refuser plutôt que d'ouvrir une session au hasard
     * entre deux identités.
     */
    java.util.List<User> findAllByEmailIgnoreCase(String email);
    boolean existsByEmail(String email);

    /** Premier compte d'un établissement (= l'admin créé à l'inscription). Vue plateforme. */
    Optional<User> findFirstByEtablissementIdOrderByIdAsc(Long etablissementId);

    /** Tous les comptes d'un établissement (fiche plateforme : liste des administrateurs). */
    java.util.List<User> findByEtablissementIdOrderByIdAsc(Long etablissementId);
}
