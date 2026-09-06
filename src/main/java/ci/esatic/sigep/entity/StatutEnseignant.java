package ci.esatic.sigep.entity;

public enum StatutEnseignant {

    /** Compte actif : l'enseignant peut se connecter et émarger. */
    VALIDATED,

    /** Créé sans compte utilisable (import sans e-mail) : la connexion est refusée. */
    PENDING,

    /** Inscription refusée par l'administration. */
    REJECTED,

    /**
     * Enseignant qui a quitté l'établissement.
     *
     * <p>Distinct de {@code REJECTED}, qui signifie « on n'a pas voulu de ce compte » — un mot
     * déplacé pour quelqu'un qui part en retraite après vingt ans. L'effet technique est le même
     * (plus aucun accès, sessions coupées), mais l'intitulé dit la vérité, et ces enseignants
     * sortent de la liste courante sans que leur historique soit touché : leurs émargements et
     * leurs rapports signés restent la preuve des heures faites.
     */
    ARCHIVE
}
