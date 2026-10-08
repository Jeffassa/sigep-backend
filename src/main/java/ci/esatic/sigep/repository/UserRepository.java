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

    /**
     * Comptes d'UN établissement portant cette adresse, quelle que soit la casse.
     *
     * <p>La variante non bornée ci-dessus cherche dans toute la base, et c'est un levier contre
     * nos propres clients : la contrainte d'unicité distingue les majuscules, si bien qu'un
     * établissement peut créer chez lui un compte « Admin@ecole-voisine.ci » et rendre du même
     * coup l'adresse « admin@ecole-voisine.ci » <b>ambiguë</b> — donc refusée — à la connexion
     * SSO de l'école voisine. Un concurrent fermerait ainsi la porte d'un autre sans jamais
     * toucher à ses données.
     *
     * <p>En bornant la recherche à l'établissement qui s'authentifie, ce qui se passe chez les
     * autres cesse d'avoir le moindre effet ici.
     */
    java.util.List<User> findAllByEmailIgnoreCaseAndEtablissementId(String email, Long etablissementId);
    boolean existsByEmail(String email);

    /** Premier compte d'un établissement (= l'admin créé à l'inscription). Vue plateforme. */
    Optional<User> findFirstByEtablissementIdOrderByIdAsc(Long etablissementId);

    /** Tous les comptes d'un établissement (fiche plateforme : liste des administrateurs). */
    java.util.List<User> findByEtablissementIdOrderByIdAsc(Long etablissementId);
}
