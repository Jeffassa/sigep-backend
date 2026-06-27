package ci.esatic.sigep.entity;

import ci.esatic.sigep.tenant.TenantListener;
import ci.esatic.sigep.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

@Entity
@Table(name = "emargements")
@Filter(name = "tenantFilter", condition = "etablissement_id = :tenantId")
@EntityListeners(TenantListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Emargement implements TenantScoped {

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

    // Multi-tenant : établissement propriétaire (isolation + estampillage automatique).
    @Column(name = "etablissement_id")
    private Long etablissementId;
}
