package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.EvenementSecurite;
import ci.esatic.sigep.entity.SeveriteEvenement;
import ci.esatic.sigep.entity.TypeEvenement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface EvenementSecuriteRepository extends JpaRepository<EvenementSecurite, Long> {

    Page<EvenementSecurite> findAllByOrderByDateHeureDesc(Pageable pageable);

    Page<EvenementSecurite> findBySeveriteOrderByDateHeureDesc(SeveriteEvenement severite, Pageable pageable);

    Page<EvenementSecurite> findByTypeOrderByDateHeureDesc(TypeEvenement type, Pageable pageable);

    Page<EvenementSecurite> findByEtablissementIdOrderByDateHeureDesc(Long etablissementId, Pageable pageable);

    /**
     * Bilan par établissement.
     *
     * <p>C'est la lecture qui permet d'AGIR : un total global dit qu'il se passe quelque chose,
     * il ne dit pas chez qui. Les établissements les plus touchés remontent en tête — d'abord
     * par nombre de faits critiques, ensuite par volume.
     */
    @Query("""
            SELECT e.etablissementId AS etablissementId,
                   COUNT(e) AS total,
                   SUM(CASE WHEN e.severite = ci.esatic.sigep.entity.SeveriteEvenement.CRITIQUE THEN 1 ELSE 0 END) AS critiques,
                   MAX(e.dateHeure) AS dernier
            FROM EvenementSecurite e
            WHERE e.dateHeure > :depuis AND e.etablissementId IS NOT NULL
            GROUP BY e.etablissementId
            ORDER BY SUM(CASE WHEN e.severite = ci.esatic.sigep.entity.SeveriteEvenement.CRITIQUE THEN 1 ELSE 0 END) DESC,
                     COUNT(e) DESC
            """)
    List<BilanEtablissement> bilanParEtablissement(@Param("depuis") LocalDateTime depuis);

    long countByDateHeureAfter(LocalDateTime depuis);

    long countBySeveriteAndDateHeureAfter(SeveriteEvenement severite, LocalDateTime depuis);

    long countByTypeAndDateHeureAfter(TypeEvenement type, LocalDateTime depuis);

    /**
     * Adresses les plus insistantes sur la période.
     *
     * <p>C'est la lecture qui distingue une erreur de saisie d'une attaque : un utilisateur qui se
     * trompe produit deux ou trois lignes, un robot en produit des centaines depuis la même IP.
     */
    @Query("""
            SELECT e.ip AS ip, COUNT(e) AS total, MAX(e.dateHeure) AS dernier
            FROM EvenementSecurite e
            WHERE e.dateHeure > :depuis AND e.ip IS NOT NULL AND e.severite <> ci.esatic.sigep.entity.SeveriteEvenement.INFO
            GROUP BY e.ip
            ORDER BY COUNT(e) DESC
            """)
    List<SourceInsistante> sourcesLesPlusInsistantes(@Param("depuis") LocalDateTime depuis, Pageable pageable);

    /** Répartition par nature, pour voir d'un coup d'œil ce qui domine. */
    @Query("""
            SELECT e.type AS type, COUNT(e) AS total
            FROM EvenementSecurite e
            WHERE e.dateHeure > :depuis
            GROUP BY e.type
            ORDER BY COUNT(e) DESC
            """)
    List<RepartitionType> repartitionParType(@Param("depuis") LocalDateTime depuis);

    /** Purge de rétention : un journal de sécurité qui grossit sans fin finit par coûter plus qu'il ne sert. */
    @Modifying
    @Query("DELETE FROM EvenementSecurite e WHERE e.dateHeure < :avant")
    int purgerAvant(@Param("avant") LocalDateTime avant);

    interface SourceInsistante {
        String getIp();
        long getTotal();
        LocalDateTime getDernier();
    }

    interface RepartitionType {
        TypeEvenement getType();
        long getTotal();
    }

    interface BilanEtablissement {
        Long getEtablissementId();
        long getTotal();
        long getCritiques();
        LocalDateTime getDernier();
    }
}
