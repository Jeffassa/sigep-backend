package ci.esatic.sigep.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "emargements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Emargement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seance_id", nullable = false, unique = true)
    private Seance seance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enseignant_id", nullable = false)
    private Enseignant enseignant;

    @Column(nullable = false)
    private LocalDateTime dateHeure;

    // true si l'émargement a été fait après la fin de la séance (rattrapage d'oubli)
    @Column(nullable = false)
    @Builder.Default
    private boolean enRetard = false;

    // true si émargé hors-ligne (file d'attente) : présence NON vérifiée par QR
    @Column(nullable = false)
    @Builder.Default
    private boolean horsLigne = false;

    @Column(columnDefinition = "TEXT")
    private String signatureBase64;

    // Token QR scanné (pour audit). TEXT : le JWT du QR universel dépasse 255 caractères.
    @Column(columnDefinition = "TEXT")
    private String qrTokenUtilise;
}
