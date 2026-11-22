package ci.esatic.sigep.entity;

/** Rapprochement d'un établissement client avec les listes officielles de l'État. */
public enum StatutReconnaissance {
    /** Son nom figure, sans ambiguïté, sur une liste officielle. */
    RECONNU("Reconnu"),
    /** Aucune correspondance sûre : la plateforme doit examiner son autorisation. */
    A_VERIFIER("À vérifier"),
    /** Examiné par la plateforme et reconnu, au vu d'un document. */
    VERIFIE("Vérifié"),
    /** Examiné par la plateforme : aucune autorisation de l'État établie. */
    NON_RECONNU("Non reconnu");

    private final String libelle;

    StatutReconnaissance(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
