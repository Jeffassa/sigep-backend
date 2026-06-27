package ci.esatic.sigep.entity;

import ci.esatic.sigep.tenant.TenantListener;
import ci.esatic.sigep.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "salles")
@Filter(name = "tenantFilter", condition = "etablissement_id = :tenantId")
@EntityListeners(TenantListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Salle implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Identifiant court de la salle (ex : A101). Sert aussi de jeton dans le QR
    // d'émargement : doit rester alphanumérique sans espace (cf. QrController.sanitize).
    @Column(unique = true, nullable = false)
    private String libelle;

    private String batiment;

    private Integer capacite;

    // Multi-tenant : établissement propriétaire (isolation + estampillage automatique).
    @Column(name = "etablissement_id")
    private Long etablissementId;
}
