package ci.esatic.sigep.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/** Un mégaoctet de l'APK : le fichier s'écrit et se sert morceau par morceau. */
@Entity
@Table(name = "application_mobile_morceaux")
@IdClass(ApplicationMobileMorceau.Cle.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationMobileMorceau {

    public static final int TAILLE = 1024 * 1024;

    @Id
    @Column(name = "application_id")
    private Long applicationId;

    @Id
    private Integer numero;

    @Column(nullable = false, length = TAILLE)
    private byte[] donnees;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cle implements Serializable {
        private Long applicationId;
        private Integer numero;
    }
}
