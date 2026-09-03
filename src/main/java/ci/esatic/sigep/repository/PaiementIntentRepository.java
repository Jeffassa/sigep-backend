package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.PaiementIntent;
import ci.esatic.sigep.entity.StatutIntent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaiementIntentRepository extends JpaRepository<PaiementIntent, Long> {

    Optional<PaiementIntent> findByReference(String reference);

    /** Intentions encore en attente de confirmation : balayées par le relanceur périodique. */
    List<PaiementIntent> findByStatutOrderByDateCreationAsc(StatutIntent statut);

    /** Intentions d'un établissement (suppression RGPD, historique). */
    List<PaiementIntent> findByEtablissementIdOrderByDateCreationDesc(Long etablissementId);

    /** Purge : intentions terminées et anciennes. */
    List<PaiementIntent> findByStatutNotAndDateCreationBefore(StatutIntent statut, LocalDateTime avant);
}
