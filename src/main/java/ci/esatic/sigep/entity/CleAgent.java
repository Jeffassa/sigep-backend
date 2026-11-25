package ci.esatic.sigep.entity;

import ci.esatic.sigep.tenant.TenantListener;
import ci.esatic.sigep.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/**
 * Clé d'un agent qui agit sur le tableau de bord d'un établissement.
 *
 * <p>Contrairement à une clé d'API, qui ouvre un établissement en lecture, elle est liée à un
 * administrateur précis : l'agent emprunte ses droits, tels qu'ils sont au moment de chaque
 * appel. Un administrateur dont le profil change, ou qui perd son accès, emporte son agent avec
 * lui. Seule l'empreinte est conservée.
 */
@Entity
@Table(name = "cles_agent")
@Filter(name = "tenantFilter", condition = "etablissement_id = :tenantId")
@EntityListeners(TenantListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CleAgent implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String libelle;

    /** SHA-256 de la clé, en hexadécimal. */
    @Column(nullable = false, length = 64, unique = true)
    private String empreinte;

    /** Début de la clé, en clair, pour qu'on reconnaisse la sienne dans la liste. */
    @Column(nullable = false, length = 20)
    private String prefixe;

    @Column(name = "etablissement_id")
    private Long etablissementId;

    /** Compte administrateur dont l'agent emprunte les droits. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "creee_le", nullable = false)
    @Builder.Default
    private LocalDateTime creeeLe = LocalDateTime.now();

    @Column(name = "derniere_utilisation")
    private LocalDateTime derniereUtilisation;

    /** Non nulle = clé révoquée. La ligne est gardée pour la trace. */
    @Column(name = "revoquee_le")
    private LocalDateTime revoqueeLe;

    public boolean estActive() {
        return revoqueeLe == null;
    }
}
