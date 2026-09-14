package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.CleApi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CleApiRepository extends JpaRepository<CleApi, Long> {

    /** Restreint à l'établissement courant par le filtre Hibernate. */
    List<CleApi> findAllByOrderByCreeeLeDesc();

    /**
     * Recherche par empreinte, SANS filtre d'établissement.
     *
     * <p>C'est volontaire et c'est le seul endroit où cela se justifie : au moment de
     * l'authentification, on ignore encore de quel établissement relève l'appel — c'est
     * précisément la clé qui va le dire. La requête native contourne donc le filtre, et
     * l'établissement lu ici devient ensuite le seul périmètre visible de la requête.
     */
    @Query(value = "SELECT * FROM cles_api WHERE empreinte = :empreinte AND revoquee_le IS NULL",
            nativeQuery = true)
    Optional<CleApi> chercherActiveParEmpreinte(String empreinte);
}
