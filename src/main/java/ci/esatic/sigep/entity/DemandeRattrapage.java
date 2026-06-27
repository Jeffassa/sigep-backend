package ci.esatic.sigep.entity;

import ci.esatic.sigep.tenant.TenantListener;
import ci.esatic.sigep.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "demandes_rattrapage")
@Filter(name = "tenantFilter", condition = "etablissement_id = :tenantId")
@EntityListeners(TenantListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DemandeRattrapage implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enseignant_id", nullable = false)
    private Enseignant enseignant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matiere_id", nullable = false)
    private Matiere matiere;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "classe_id", nullable = false)
    private Classe classe;

    @Column(nullable = false)
    private LocalDate dateSouhaitee;

    @Column(nullable = false)
    private LocalTime heureSouhaitee;

    @Column(columnDefinition = "TEXT")
    private String motif;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private StatutDemande statut = StatutDemande.EN_ATTENTE;

    @Column(nullable = false)
    private LocalDateTime dateCreation;

    // Séance créée après acceptation
    @OneToOne
    @JoinColumn(name = "seance_rattrapage_id")
    private Seance seanceRattrapage;

    // Multi-tenant : établissement propriétaire (isolation + estampillage automatique).
    @Column(name = "etablissement_id")
    private Long etablissementId;

    @PrePersist
    protected void onCreate() {
        dateCreation = LocalDateTime.now();
    }
}
