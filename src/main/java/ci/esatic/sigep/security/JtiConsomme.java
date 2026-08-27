package ci.esatic.sigep.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Anti-rejeu PERSISTANT des tokens QR d'émargement (E4). Un identifiant consommé
 * {@code cle = enseignantId:jti} est stocké en base : l'anti-rejeu survit aux
 * redémarrages et fonctionne en multi-instance (contrairement à l'ancienne map mémoire).
 * {@code expireLe} borne la rétention (purge planifiée) ; le {@code jti} étant un UUID
 * unique par token, une ligne périmée non purgée ne peut jamais bloquer un nouveau token.
 */
@Entity
@Table(name = "jti_consommes")
public class JtiConsomme {

    @Id
    @Column(length = 128)
    private String cle;

    @Column(name = "expire_le", nullable = false)
    private LocalDateTime expireLe;

    protected JtiConsomme() {
    }

    public JtiConsomme(String cle, LocalDateTime expireLe) {
        this.cle = cle;
        this.expireLe = expireLe;
    }

    public String getCle() {
        return cle;
    }

    public LocalDateTime getExpireLe() {
        return expireLe;
    }
}
