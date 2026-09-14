package ci.esatic.sigep.entity;

import ci.esatic.sigep.tenant.TenantListener;
import ci.esatic.sigep.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/**
 * Clé d'accès à l'API publique d'un établissement.
 *
 * <p>Elle ouvre les données d'un établissement : elle est donc traitée comme un mot de passe.
 * Seule son empreinte est conservée, si bien qu'une base qui fuite ne livre aucune clé
 * utilisable — et que personne, pas même nous, ne peut relire une clé perdue. On en émet une
 * nouvelle, et l'ancienne est révoquée.
 */
@Entity
@Table(name = "cles_api")
@Filter(name = "tenantFilter", condition = "etablissement_id = :tenantId")
@EntityListeners(TenantListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CleApi implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nom donné par l'administrateur, pour savoir à quoi sert cette clé. */
    @Column(nullable = false, length = 120)
    private String libelle;

    /** SHA-256 de la clé, en hexadécimal. */
    @Column(nullable = false, length = 64, unique = true)
    private String empreinte;

    /** Début de la clé, conservé en clair pour qu'on reconnaisse la sienne dans la liste. */
    @Column(nullable = false, length = 16)
    private String prefixe;

    @Column(name = "etablissement_id")
    private Long etablissementId;

    @Column(name = "creee_le", nullable = false)
    @Builder.Default
    private LocalDateTime creeeLe = LocalDateTime.now();

    /** Renseignée à chaque appel : permet de repérer une clé oubliée, ou détournée. */
    @Column(name = "derniere_utilisation")
    private LocalDateTime derniereUtilisation;

    /** Non nulle = clé révoquée. La ligne est gardée pour la trace de ce qui a existé. */
    @Column(name = "revoquee_le")
    private LocalDateTime revoqueeLe;

    public boolean estActive() {
        return revoqueeLe == null;
    }
}
