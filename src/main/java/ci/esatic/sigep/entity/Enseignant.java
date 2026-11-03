package ci.esatic.sigep.entity;

import ci.esatic.sigep.tenant.TenantListener;
import ci.esatic.sigep.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "enseignants",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_enseignant_etab_matricule",
                        columnNames = {"etablissement_id", "matricule"}),
                @UniqueConstraint(name = "uk_enseignant_etab_user",
                        columnNames = {"etablissement_id", "user_id"})
        })
@Filter(name = "tenantFilter", condition = "etablissement_id = :tenantId")
@EntityListeners(TenantListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Enseignant implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Unique PAR établissement (cf. @Table) : deux écoles peuvent réutiliser le même matricule.
    @Column(nullable = false)
    private String matricule;

    @Column(nullable = false)
    private String nom;

    @Column(nullable = false)
    private String prenom;

    private String departement;

    private String grade;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private StatutEnseignant statut = StatutEnseignant.PENDING;

    private String photoUrl;

    // Un vacataire a un seul compte et une fiche dans chaque établissement où il intervient :
    // supprimer une fiche ne doit donc jamais emporter le compte par cascade.
    @ManyToOne(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    private User user;

    // Multi-tenant : établissement propriétaire (isolation + estampillage automatique).
    @Column(name = "etablissement_id")
    private Long etablissementId;
}
