package ci.esatic.sigep.entity;

import ci.esatic.sigep.tenant.TenantListener;
import ci.esatic.sigep.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;

/** Jour férié, vacances ou semestre d'un établissement. */
@Entity
@Table(name = "periodes_calendrier")
@Filter(name = "tenantFilter", condition = "etablissement_id = :tenantId")
@EntityListeners(TenantListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeriodeCalendrier implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypePeriode type;

    @Column(nullable = false, length = 120)
    private String libelle;

    @Column(nullable = false)
    private LocalDate debut;

    @Column(nullable = false)
    private LocalDate fin;

    @Column(name = "etablissement_id")
    private Long etablissementId;

    public boolean contient(LocalDate jour) {
        return !jour.isBefore(debut) && !jour.isAfter(fin);
    }
}
