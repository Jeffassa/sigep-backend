package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.AssistantConso;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface AssistantConsoRepository extends JpaRepository<AssistantConso, Long> {

    Optional<AssistantConso> findByEtablissementIdAndJour(Long etablissementId, LocalDate jour);
}
