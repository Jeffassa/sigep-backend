package ci.esatic.sigep.annuaire;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Dernière collecte d'un pays : quand, combien d'établissements, et l'erreur s'il y en a eu une. */
@Entity
@Table(name = "annuaire_collectes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnuaireCollecte {

    @Id
    @Column(name = "pays_code", length = 2)
    private String paysCode;

    @Column(name = "date_collecte")
    private LocalDateTime dateCollecte;

    private Integer nombre;

    @Column(length = 300)
    private String erreur;
}
