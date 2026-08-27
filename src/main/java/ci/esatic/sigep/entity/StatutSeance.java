package ci.esatic.sigep.entity;

public enum StatutSeance {
    A_FAIRE,
    EMARGE,
    EN_RETARD,
    /** Émargement hors-ligne enregistré mais présence non encore confirmée par l'admin (à valider). */
    EN_ATTENTE_VALIDATION
}
