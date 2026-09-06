package ci.esatic.sigep.entity;

/** Degré d'attention qu'appelle un évènement de sécurité. */
public enum SeveriteEvenement {

    /** Activité normale, conservée pour le contexte (qui s'est connecté, quand). */
    INFO,

    /** Refus légitime du système. Isolé, sans gravité ; répété, c'est un signal. */
    ALERTE,

    /** Demande un examen humain : franchissement de cloisonnement, code de secours, signature forgée. */
    CRITIQUE
}
