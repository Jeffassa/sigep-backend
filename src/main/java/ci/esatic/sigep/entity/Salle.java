package ci.esatic.sigep.entity;

import ci.esatic.sigep.tenant.TenantListener;
import ci.esatic.sigep.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "salles",
        uniqueConstraints = @UniqueConstraint(name = "uk_salle_etab_libelle",
                columnNames = {"etablissement_id", "libelle"}))
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

    // Identifiant court de la salle (ex : A101). Unique PAR établissement (cf. @Table).
    @Column(nullable = false)
    private String libelle;

    private String batiment;
    /**
     * Campus de rattachement. NULL pour les salles saisies avant la notion de campus : exiger
     * un rattachement rétroactif aurait bloqué tous les établissements déjà en service.
     */
    @Column(name = "campus_id")
    private Long campusId;

    private Integer capacite;

    // Multi-tenant : établissement propriétaire (isolation + estampillage automatique).
    @Column(name = "etablissement_id")
    private Long etablissementId;
}
