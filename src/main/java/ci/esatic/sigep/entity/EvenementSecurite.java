package ci.esatic.sigep.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Évènement de sécurité observé par la plateforme.
 *
 * <p><b>Volontairement GLOBALE, hors cloisonnement par établissement.</b> Deux raisons : une part
 * de ces évènements survient AVANT toute identification d'établissement (une connexion refusée, un
 * débit dépassé sur une IP anonyme) et n'aurait donc aucun tenant à porter ; et surtout, ceux qui
 * comptent le plus sont précisément les tentatives de FRANCHIR le cloisonnement — les filtrer par
 * tenant reviendrait à masquer au super-administrateur ce qu'il doit voir en premier.
 *
 * <p>Le champ {@code etablissementId} est renseigné quand il est connu, à titre de contexte, mais
 * il n'est jamais utilisé comme filtre de lecture. La console est réservée au super-administrateur
 * ({@code /plateforme/**}), qui a par définition une vue d'ensemble.
 *
 * <p><b>Ce qu'on n'écrit pas ici :</b> ni mot de passe, ni jeton, ni QR, ni code à usage unique.
 * Un journal de sécurité qui recopierait les secrets qu'il surveille deviendrait la première cible
 * de l'attaquant. {@code details} ne reçoit que des éléments non réutilisables.
 */
@Entity
@Table(name = "evenements_securite")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvenementSecurite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "date_heure", nullable = false)
    private LocalDateTime dateHeure;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private TypeEvenement type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SeveriteEvenement severite;

    /** IP réelle de l'appelant, résolue derrière le proxy de l'hébergeur. */
    @Column(length = 64)
    private String ip;

    /** E-mail, matricule ou identifiant présenté. Jamais le secret associé. */
    @Column(length = 180)
    private String identifiant;

    /** Établissement concerné lorsqu'il est connu — contexte, jamais filtre. */
    @Column(name = "etablissement_id")
    private Long etablissementId;

    /** Chemin visé, utile pour distinguer un balayage d'une erreur de saisie. */
    @Column(length = 255)
    private String chemin;

    /** Précision lisible, sans donnée réutilisable. */
    @Column(length = 500)
    private String details;
}
