package ci.esatic.sigep.entity;

import ci.esatic.sigep.tenant.TenantListener;
import ci.esatic.sigep.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/** Une action de l'agent — faite ou seulement simulée — telle que l'établissement peut la relire. */
@Entity
@Table(name = "journal_agent")
@Filter(name = "tenantFilter", condition = "etablissement_id = :tenantId")
@EntityListeners(TenantListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JournalAgent implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "etablissement_id")
    private Long etablissementId;

    @Column(name = "cle_id", nullable = false)
    private Long cleId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "date_heure", nullable = false)
    private LocalDateTime dateHeure;

    @Column(nullable = false, length = 10)
    private String methode;

    @Column(nullable = false, length = 255)
    private String chemin;

    @Column(name = "statut_http", nullable = false)
    private int statutHttp;

    @Column(nullable = false)
    private boolean simulation;

    /** Ce que l'agent a fait, en une phrase. */
    @Column(length = 500)
    private String resume;

    public boolean estReussi() {
        return statutHttp >= 200 && statutHttp < 300;
    }
}
