package ci.esatic.sigep.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Annonce de la plateforme, affichée en bandeau dans la console de chaque établissement entre
 * {@code debut} et {@code fin}. Sans fin, elle reste affichée jusqu'à ce qu'on la close.
 *
 * <p>Pas de filtre d'établissement : l'annonce appartient à la plateforme et s'adresse à tous.
 */
@Entity
@Table(name = "annonces")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Annonce {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeAnnonce type;

    @Column(nullable = false, length = 300)
    private String message;

    @Column(nullable = false)
    private LocalDateTime debut;

    private LocalDateTime fin;

    /** Adresse du super administrateur qui l'a publiée. */
    @Column(nullable = false)
    private String auteur;

    @Column(name = "creee_le", nullable = false)
    @Builder.Default
    private LocalDateTime creeeLe = LocalDateTime.now();

    /** État à l'instant donné : « À venir », « En cours » ou « Terminée ». */
    public String etat(LocalDateTime maintenant) {
        if (fin != null && !fin.isAfter(maintenant)) return "Terminée";
        return debut.isAfter(maintenant) ? "À venir" : "En cours";
    }
}
