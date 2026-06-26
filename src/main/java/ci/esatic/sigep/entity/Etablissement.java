package ci.esatic.sigep.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Établissement = TENANT du SaaS. Chaque donnée métier (utilisateurs, enseignants,
 * séances, émargements…) appartient à un seul établissement. L'isolation entre
 * établissements est assurée au Module 2 (filtre par {@code etablissement_id}).
 */
@Entity
@Table(name = "etablissements", uniqueConstraints = @UniqueConstraint(columnNames = "slug"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Etablissement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nom;

    /** Identifiant court et unique (URL / futur sous-domaine), ex. "esatic". */
    @Column(nullable = false, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Plan plan = Plan.FREE;

    @Builder.Default
    private boolean actif = true;

    /** Quota d'enseignants (0 = illimité). Utilisé pour le gating FREE (Module 3). */
    @Builder.Default
    private int maxEnseignants = 10;

    @Builder.Default
    private LocalDateTime dateCreation = LocalDateTime.now();
}
