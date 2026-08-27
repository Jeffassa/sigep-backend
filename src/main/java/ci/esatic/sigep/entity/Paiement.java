package ci.esatic.sigep.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Paiement d'abonnement reçu d'un établissement (Mobile Money, saisi par le super admin).
 * Trace comptable de la plateforme : « total encaissé », historique, revenus par période.
 * Enregistré au moment où le super admin valide un renouvellement.
 */
@Entity
// Idempotence : une même référence de transaction ne peut être enregistrée qu'une fois
// (les NULL restent multiples — saisies manuelles sans référence).
@Table(name = "paiements",
        uniqueConstraints = @UniqueConstraint(name = "uk_paiements_reference", columnNames = "reference"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Paiement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Établissement payeur (pas de filtre tenant : géré par le super admin). */
    @Column(name = "etablissement_id", nullable = false)
    private Long etablissementId;

    /** Montant reçu, en FCFA (entier). */
    @Column(nullable = false)
    private long montant;

    /** Nombre de mois crédités par ce paiement. */
    @Column(name = "mois_credites", nullable = false)
    @Builder.Default
    private int moisCredites = 1;

    /** Référence libre (n° de transaction Mobile Money, note…). */
    private String reference;

    @Column(name = "date_paiement", nullable = false)
    private LocalDateTime datePaiement;

    /** E-mail du super admin qui a enregistré le paiement (traçabilité). */
    @Column(name = "enregistre_par")
    private String enregistrePar;

    @PrePersist
    void onCreate() {
        if (datePaiement == null) {
            datePaiement = LocalDateTime.now();
        }
    }
}
