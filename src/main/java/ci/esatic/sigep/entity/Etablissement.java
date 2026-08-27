package ci.esatic.sigep.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Établissement = TENANT du SaaS. Chaque donnée métier (utilisateurs, enseignants,
 * séances, émargements…) appartient à un seul établissement. L'isolation entre
 * établissements est assurée au Module 2 (filtre par {@code etablissement_id}).
 */
@Entity
@Table(name = "etablissements", uniqueConstraints = @UniqueConstraint(columnNames = "slug"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Etablissement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nom;

    /** Identifiant court et unique (URL / futur sous-domaine), ex. "esatic". */
    @Column(nullable = false, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Plan plan = Plan.FREE;

    /**
     * Validation du dossier d'inscription par le SUPER ADMIN : une inscription self-service
     * naît EN_ATTENTE (connexion bloquée) jusqu'à validation. Les établissements créés en
     * interne (défaut, plateforme) sont VALIDE d'office.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutEtablissement statut = StatutEtablissement.VALIDE;

    @Builder.Default
    private boolean actif = true;

    /** Quota d'enseignants (0 = illimité). Utilisé pour le gating FREE (Module 3). */
    @Builder.Default
    private int maxEnseignants = 10;

    @Builder.Default
    private LocalDateTime dateCreation = LocalDateTime.now();

    /**
     * Fin de la période d'abonnement payée (plans Pro/Enterprise). {@code null} = pas
     * d'expiration (plan Free gratuit, ou tenant illimité). Au-delà de cette date,
     * l'accès au SaaS est bloqué jusqu'au renouvellement (cf. AbonnementService).
     */
    private LocalDate dateExpiration;

    // ─── Configuration propre au tenant (V16) ───────────────────────────
    // Défauts = comportement historique (ESATIC / UTC+0) pour ne rien changer
    // aux établissements existants.

    /** Fuseau horaire IANA (ex. "Africa/Abidjan"). Base des calculs d'émargement et des crons (E1). */
    @Column(nullable = false, length = 64)
    @Builder.Default
    private String fuseau = "Africa/Abidjan";

    /** Locale (ex. "fr") : langue des e-mails, PDF et prompts IA (F5). */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String locale = "fr";

    /** Type d'établissement (SUPERIEUR, SECONDAIRE, …) : contextualise l'analyse IA (F5). */
    @Column(name = "type_etablissement", nullable = false, length = 40)
    @Builder.Default
    private String typeEtablissement = "SUPERIEUR";

    /** Tolérance d'émargement AVANT le début de séance, en minutes (E7). */
    @Column(name = "tolerance_avant_minutes", nullable = false)
    @Builder.Default
    private int toleranceAvantMinutes = 15;

    /** Tolérance d'émargement APRÈS la fin de séance, en minutes (E7). */
    @Column(name = "tolerance_apres_minutes", nullable = false)
    @Builder.Default
    private int toleranceApresMinutes = 30;

    /** Nom affiché (branding) ; à défaut, {@link #nom} est utilisé (E13). */
    @Column(name = "nom_affiche")
    private String nomAffiche;

    /** URL du logo de l'établissement (branding E13). */
    @Column(name = "logo_url", length = 512)
    private String logoUrl;

    /** Couleur principale hex (ex. "#000666") ; null = couleur plateforme (E13). */
    @Column(name = "couleur_principale", length = 9)
    private String couleurPrincipale;

    /** Expéditeur des e-mails métier de ce tenant ; null = expéditeur plateforme (E9). */
    @Column(name = "email_from")
    private String emailFrom;

    /** Adresse de réponse des e-mails métier de ce tenant (E9). */
    @Column(name = "email_reply_to")
    private String emailReplyTo;

    /** Numéro Mobile Money d'encaissement propre à l'établissement (E11). */
    @Column(name = "mobile_money_numero", length = 40)
    private String mobileMoneyNumero;

    /** Clé kiosque QR propre au tenant (autorise l'affichage du QR) — remplace la variable globale (C4). */
    @Column(name = "kiosk_key", length = 64)
    private String kioskKey;

    /** Libellé affichable du tenant : nom personnalisé si défini, sinon nom légal. */
    @Transient
    public String getNomEffectif() {
        return (nomAffiche != null && !nomAffiche.isBlank()) ? nomAffiche : nom;
    }
}
