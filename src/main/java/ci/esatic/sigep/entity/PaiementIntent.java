package ci.esatic.sigep.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Intention de paiement Mobile Money (NovaSend) : ce que le client s'est engagé à payer.
 *
 * <p>NovaSend n'ayant pas de webhook, la confirmation passe par un appel serveur au statut.
 * Cette table conserve le CONTRAT attendu (établissement, plan, mois, montant) fixé à
 * l'initiation, ce qui permet :
 * <ul>
 *   <li>de vérifier que le montant réellement payé correspond bien au plan demandé
 *       (sinon un client pourrait payer une somme dérisoire et obtenir un an d'abonnement) ;</li>
 *   <li>de créditer un paiement confirmé même si le client ferme son navigateur
 *       (le relanceur périodique reprend les intentions restées EN_COURS).</li>
 * </ul>
 */
@Entity
@Table(name = "paiement_intents",
        uniqueConstraints = @UniqueConstraint(name = "uk_paiement_intents_reference", columnNames = "reference"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaiementIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Référence marchande unique (UUID) : clé d'idempotence côté NovaSend ET côté paiement. */
    @Column(nullable = false, length = 64)
    private String reference;

    @Column(name = "etablissement_id", nullable = false)
    private Long etablissementId;

    /** Plan acheté (contrat figé à l'initiation). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Plan plan;

    @Column(nullable = false)
    private int mois;

    /** Montant attendu, en unité majeure de la devise. Sert de contrôle anti-écart. */
    @Column(name = "montant_attendu", nullable = false)
    private long montantAttendu;

    @Column(nullable = false, length = 8)
    private String devise;

    @Column(length = 32)
    private String msisdn;

    /** Opérateur choisi (WAVE, ORANGE, MOMO, MOOV). */
    @Column(length = 20)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutIntent statut = StatutIntent.EN_COURS;

    /** Identifiant de session NovaSend (pr_...). */
    @Column(name = "novasend_id", length = 80)
    private String novasendId;

    /** Lien de confirmation renvoyé par NovaSend (deep link Wave, page NovaSend…). */
    @Column(name = "payment_url", length = 512)
    private String paymentUrl;

    /** Dernier message d'état/erreur (diagnostic). */
    @Column(length = 512)
    private String message;

    @Column(name = "date_creation", nullable = false)
    private LocalDateTime dateCreation;

    @Column(name = "date_maj")
    private LocalDateTime dateMaj;

    @PrePersist
    void onCreate() {
        if (dateCreation == null) dateCreation = LocalDateTime.now();
        dateMaj = dateCreation;
    }

    @PreUpdate
    void onUpdate() {
        dateMaj = LocalDateTime.now();
    }
}
