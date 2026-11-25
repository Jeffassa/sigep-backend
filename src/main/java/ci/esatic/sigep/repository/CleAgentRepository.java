package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.CleAgent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CleAgentRepository extends JpaRepository<CleAgent, Long> {

    /** Restreint à l'établissement courant par le filtre Hibernate. */
    List<CleAgent> findAllByOrderByCreeeLeDesc();

    /**
     * Recherche par empreinte, SANS filtre d'établissement : au moment de l'authentification,
     * c'est la clé qui dira de quel établissement relève l'appel (même raison que pour les clés
     * d'API, cf. {@link CleApiRepository#chercherActiveParEmpreinte}).
     */
    @Query(value = "SELECT * FROM cles_agent WHERE empreinte = :empreinte AND revoquee_le IS NULL",
            nativeQuery = true)
    Optional<CleAgent> chercherActiveParEmpreinte(String empreinte);
}
