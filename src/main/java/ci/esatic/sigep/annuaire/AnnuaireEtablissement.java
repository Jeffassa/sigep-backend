package ci.esatic.sigep.annuaire;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Une école ou une université de l'UEMOA, collectée depuis OpenStreetMap. Sert uniquement à
 * proposer le nom à l'inscription : ce n'est pas un client, et aucune donnée d'un client n'y figure.
 */
@Entity
@Table(name = "annuaire_etablissements",
        uniqueConstraints = @UniqueConstraint(columnNames = {"source", "source_id"}),
        indexes = {@Index(name = "idx_annuaire_pays", columnList = "pays_code"),
                   @Index(name = "idx_annuaire_nom", columnList = "nom_recherche")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnuaireEtablissement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String nom;

    /** Le nom en minuscules, sans accents ni ponctuation : la clé de recherche. */
    @Column(name = "nom_recherche", nullable = false, length = 255)
    private String nomRecherche;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TypeAnnuaire type;

    @Column(length = 120)
    private String ville;

    @Column(name = "pays_code", nullable = false, length = 2)
    private String paysCode;

    /** Origine de la fiche ({@code OSM}) et son identifiant chez elle ({@code node/123}). */
    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "source_id", nullable = false, length = 40)
    private String sourceId;

    private Double latitude;
    private Double longitude;

    @Column(name = "date_collecte", nullable = false)
    private LocalDateTime dateCollecte;
}
