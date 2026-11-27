package ci.esatic.sigep.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * L'APK de l'application Android déposé par la plateforme. Une seule version est gardée ; son
 * contenu est rangé en morceaux ({@link ApplicationMobileMorceau}).
 */
@Entity
@Table(name = "application_mobile")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationMobile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String version;

    /** Taille du fichier, en octets. */
    @Column(nullable = false)
    private long taille;

    /** Empreinte SHA-256 du fichier, en hexadécimal. */
    @Column(nullable = false, length = 64)
    private String sha256;

    /** Nombre de morceaux, numérotés de 0 à morceaux - 1. */
    @Column(nullable = false)
    private int morceaux;

    @Column(name = "date_depot", nullable = false)
    private LocalDateTime dateDepot;

    @Column(name = "depose_par", length = 180)
    private String deposePar;
}
