package ci.esatic.sigep.entity;

import ci.esatic.sigep.tenant.TenantListener;
import ci.esatic.sigep.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "classes")
@Filter(name = "tenantFilter", condition = "etablissement_id = :tenantId")
@EntityListeners(TenantListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Classe implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String libelle;

    private String filiere;

    private Integer niveau;

    // Multi-tenant : établissement propriétaire (isolation + estampillage automatique).
    @Column(name = "etablissement_id")
    private Long etablissementId;
}
