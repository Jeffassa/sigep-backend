package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.DemandeRattrapage;
import ci.esatic.sigep.entity.StatutDemande;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DemandeRattrapageRepository extends JpaRepository<DemandeRattrapage, Long> {
    List<DemandeRattrapage> findByEnseignantIdOrderByDateCreationDesc(Long enseignantId);

    /** Sert à refuser la suppression d'un enseignant ayant des demandes à son nom. */
    long countByEnseignantId(Long enseignantId);
    List<DemandeRattrapage> findByStatutOrderByDateCreationDesc(StatutDemande statut);
    List<DemandeRattrapage> findAllByOrderByDateCreationDesc();
    long countByStatut(StatutDemande statut);
}
