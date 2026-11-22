package ci.esatic.sigep.annuaire;

/** Nature d'un établissement d'une liste officielle ; le rang ordonne les suggestions. */
public enum CategorieOfficielle {
    UNIVERSITE_PUBLIQUE("Université publique", 0),
    GRANDE_ECOLE_PUBLIQUE("Grande école publique", 1),
    UNIVERSITE_PRIVEE("Université privée", 0),
    GRANDE_ECOLE_PRIVEE("Grande école privée", 1),
    SECONDAIRE_PUBLIC("Collège ou lycée public", 2),
    SECONDAIRE_PRIVE("Collège ou lycée privé", 2);

    private final String libelle;
    private final int rang;

    CategorieOfficielle(String libelle, int rang) {
        this.libelle = libelle;
        this.rang = rang;
    }

    public String getLibelle() { return libelle; }

    public int getRang() { return rang; }
}
