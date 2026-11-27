package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.JournalAgent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface JournalAgentRepository extends JpaRepository<JournalAgent, Long> {

    /** Restreint à l'établissement courant par le filtre Hibernate. */
    List<JournalAgent> findTop50ByOrderByDateHeureDesc();

    @Modifying
    @Query("DELETE FROM JournalAgent j WHERE j.dateHeure < :limite")
    int purgerAvant(LocalDateTime limite);
}
