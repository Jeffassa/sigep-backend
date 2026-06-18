package ci.esatic.sigep.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "salles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Salle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Identifiant court de la salle (ex : A101). Sert aussi de jeton dans le QR
    // d'émargement : doit rester alphanumérique sans espace (cf. QrController.sanitize).
    @Column(unique = true, nullable = false)
    private String libelle;

    private String batiment;

    private Integer capacite;
}
