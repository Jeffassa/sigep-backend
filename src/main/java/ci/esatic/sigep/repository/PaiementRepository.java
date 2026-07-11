package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.Paiement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PaiementRepository extends JpaRepository<Paiement, Long> {

    /** Historique global (le plus récent d'abord) — vue plateforme. */
    List<Paiement> findAllByOrderByDatePaiementDesc();

    /** Historique d'un établissement. */
    List<Paiement> findByEtablissementIdOrderByDatePaiementDesc(Long etablissementId);

    /** Total encaissé, toutes périodes. */
    @Query("SELECT COALESCE(SUM(p.montant), 0) FROM Paiement p")
    long totalEncaisse();

    /** Total encaissé depuis une date (ex. début du mois courant). */
    @Query("SELECT COALESCE(SUM(p.montant), 0) FROM Paiement p WHERE p.datePaiement >= :depuis")
    long encaisseDepuis(@Param("depuis") LocalDateTime depuis);

    /** Total encaissé pour un établissement. */
    @Query("SELECT COALESCE(SUM(p.montant), 0) FROM Paiement p WHERE p.etablissementId = :id")
    long totalParEtablissement(@Param("id") Long etablissementId);
}
