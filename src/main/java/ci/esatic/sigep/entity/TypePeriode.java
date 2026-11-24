package ci.esatic.sigep.entity;

public enum TypePeriode {
    FERIE,
    VACANCES,
    SEMESTRE;

    /** L'établissement est fermé : aucun cours n'y est planifié par répétition. */
    public boolean ferme() {
        return this != SEMESTRE;
    }
}
