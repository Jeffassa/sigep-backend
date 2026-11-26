package ci.esatic.sigep.entity;

import ci.esatic.sigep.tenant.TenantListener;
import ci.esatic.sigep.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;

/** Consommation de l'assistant de la console, par établissement et par jour. */
@Entity
@Table(name = "assistant_conso",
        uniqueConstraints = @UniqueConstraint(name = "uk_assistant_conso_jour", columnNames = {"etablissement_id", "jour"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Filter(name = "tenantFilter", condition = "etablissement_id = :tenantId")
@EntityListeners(TenantListener.class)
public class AssistantConso implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "etablissement_id", nullable = false)
    private Long etablissementId;

    @Column(nullable = false)
    private LocalDate jour;

    /** Messages de l'administrateur traités ce jour-là. */
    @Builder.Default
    @Column(nullable = false)
    private int requetes = 0;

    @Builder.Default
    @Column(name = "jetons_entree", nullable = false)
    private long jetonsEntree = 0;

    @Builder.Default
    @Column(name = "jetons_sortie", nullable = false)
    private long jetonsSortie = 0;
}
