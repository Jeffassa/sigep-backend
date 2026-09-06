package ci.esatic.sigep.entity;

/**
 * Nature d'un évènement de sécurité observé par la plateforme.
 *
 * <p>Chaque valeur correspond à une détection qui EXISTE déjà dans le code : ce journal ne crée
 * aucun contrôle, il donne une mémoire consultable à des refus qui ne laissaient jusqu'ici
 * qu'une ligne dans les journaux du serveur — illisibles depuis l'interface, et perdus à chaque
 * redémarrage de l'hébergement.
 */
public enum TypeEvenement {

    /** Mot de passe refusé sur /admin-login. Répété depuis une même IP : attaque par dictionnaire. */
    CONNEXION_ADMIN_ECHOUEE("Connexion admin refusée"),

    /** Connexion administrateur menée à son terme (second facteur compris). */
    CONNEXION_ADMIN_REUSSIE("Connexion admin réussie"),

    /** Code à 6 chiffres erroné. En rafale : quelqu'un tente de deviner le second facteur. */
    OTP_ECHOUE("Second facteur refusé"),

    /** Ouverture par code de secours. Hors incident connu, c'est le signe d'une compromission. */
    OTP_CODE_SECOURS("Code de secours utilisé"),

    /** Plafond de requêtes atteint : flood, balayage, ou force brute. */
    DEBIT_DEPASSE("Débit dépassé"),

    /** QR d'un AUTRE établissement présenté à l'émargement : tentative de franchir le cloisonnement. */
    QR_AUTRE_ETABLISSEMENT("QR d'un autre établissement"),

    /** QR déjà consommé, rejoué. Typiquement une capture d'écran partagée. */
    QR_REJOUE("QR rejoué"),

    /** Séance rattachée à un autre établissement que celui de l'enseignant. */
    SEANCE_AUTRE_ETABLISSEMENT("Séance d'un autre établissement"),

    /** Webhook de paiement dont la signature ne correspond pas : appel forgé, ou secret désaligné. */
    WEBHOOK_SIGNATURE_INVALIDE("Webhook non signé ou invalide"),

    /** Plafond mensuel d'émargements hors-ligne atteint par un enseignant. */
    PLAFOND_HORS_LIGNE("Plafond hors-ligne atteint"),

    /** Compte authentifié sans établissement rattaché : anomalie d'isolation. */
    ACCES_SANS_ETABLISSEMENT("Accès sans établissement"),

    /**
     * Action corrective menée par le super-administrateur.
     *
     * <p>Consignée au même titre que les incidents : savoir QUI a coupé les sessions d'un
     * établissement, et quand, fait partie de ce qu'on veut relire après coup.
     */
    REMEDIATION("Action corrective");

    private final String libelle;

    TypeEvenement(String libelle) {
        this.libelle = libelle;
    }

    /** Intitulé destiné à l'écran : les constantes techniques ne se lisent pas. */
    public String getLibelle() {
        return libelle;
    }
}
