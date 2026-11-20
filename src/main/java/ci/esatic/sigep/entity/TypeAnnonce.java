package ci.esatic.sigep.entity;

/** Nature d'une annonce de la plateforme : elle fixe son libellé, son icône et son insistance. */
public enum TypeAnnonce {
    INFORMATION("Information", "info"),
    NOUVEAUTE("Nouveauté", "new_releases"),
    /** Seule annonce qu'on ne peut pas masquer : elle prévient d'une coupure. */
    MAINTENANCE("Maintenance", "engineering");

    private final String libelle;
    private final String icone;

    TypeAnnonce(String libelle, String icone) {
        this.libelle = libelle;
        this.icone = icone;
    }

    public String getLibelle() { return libelle; }

    public String getIcone() { return icone; }

    public boolean masquable() { return this != MAINTENANCE; }
}
