package ci.esatic.sigep.entity;

import ci.esatic.sigep.tenant.TenantListener;
import ci.esatic.sigep.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

/**
 * Site physique d'un établissement : il regroupe des salles.
 *
 * <p>La notion était vendue — « 1 campus » au plan gratuit, « multi-campus » au plan Pro — sans
 * exister nulle part dans le produit. Un établissement qui tient plusieurs sites, une école avec
 * son annexe ou une université répartie sur deux villes, sépare désormais ses salles et lit ses
 * présences site par site.
 */
@Entity
@Table(name = "campus",
        uniqueConstraints = @UniqueConstraint(name = "uk_campus_etab_nom",
                columnNames = {"etablissement_id", "nom"}))
@Filter(name = "tenantFilter", condition = "etablissement_id = :tenantId")
@EntityListeners(TenantListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Campus implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nom du site, unique par établissement (cf. contrainte de table). */
    @Column(nullable = false, length = 150)
    private String nom;

    @Column(length = 255)
    private String adresse;

    /** Multi-tenant : établissement propriétaire (isolation + estampillage automatique). */
    @Column(name = "etablissement_id")
    private Long etablissementId;
}
