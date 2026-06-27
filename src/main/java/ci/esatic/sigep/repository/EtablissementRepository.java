package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.Etablissement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EtablissementRepository extends JpaRepository<Etablissement, Long> {
    Optional<Etablissement> findBySlug(String slug);

    /** Établissements actifs (pour itérer les tâches planifiées par tenant). */
    List<Etablissement> findByActifTrue();
}
