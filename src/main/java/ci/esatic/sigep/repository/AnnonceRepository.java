package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.Annonce;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AnnonceRepository extends JpaRepository<Annonce, Long> {

    /** Annonces en cours ou à venir : celles qui ne sont pas encore terminées. */
    List<Annonce> findByFinIsNullOrFinAfter(LocalDateTime maintenant);

    List<Annonce> findTop30ByOrderByCreeeLeDesc();
}
