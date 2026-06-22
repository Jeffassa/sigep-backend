package ci.esatic.sigep.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "rapports_pdf")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RapportPdf {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enseignant_id", nullable = false)
    private Enseignant enseignant;

    @Column(nullable = false)
    private LocalDate periodeDebut;

    @Column(nullable = false)
    private LocalDate periodeFin;

    @Column(nullable = false)
    private String nomFichier;

    @Column(nullable = false)
    private String cheminFichier;

    // Octets du PDF stockés en base (bytea) : le disque de l'hébergeur est éphémère
    // (fichier perdu au redéploiement/veille) -> la base est la source fiable du téléchargement.
    @Column(name = "contenu_pdf")
    private byte[] contenuPdf;

    private Long tailleFichierOctets;

    @Column(nullable = false)
    private LocalDateTime dateGeneration;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TypeRapport type = TypeRapport.HEBDOMADAIRE;

    @PrePersist
    protected void onCreate() {
        dateGeneration = LocalDateTime.now();
    }
}
