package ci.esatic.sigep.annuaire;

import java.util.Locale;

/** Nature d'un établissement de l'annuaire, déduite des étiquettes OpenStreetMap et du nom. */
public enum TypeAnnuaire {
    UNIVERSITE("Université"),
    ECOLE_SUPERIEURE("Enseignement supérieur"),
    LYCEE("Lycée"),
    COLLEGE("Collège"),
    ECOLE("École");

    private final String libelle;

    TypeAnnuaire(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }

    /**
     * {@code amenity=university} ou {@code college} d'OpenStreetMap, sinon le nom tranche :
     * beaucoup d'écoles sont simplement étiquetées {@code school}.
     */
    public static TypeAnnuaire deduire(String amenity, String nomRecherche) {
        String n = nomRecherche == null ? "" : " " + nomRecherche.toLowerCase(Locale.ROOT) + " ";
        if ("university".equals(amenity) || n.contains(" universite ") || n.contains(" university ")) return UNIVERSITE;
        if ("college".equals(amenity) && !n.contains(" college ") && !n.contains(" lycee ")) return ECOLE_SUPERIEURE;
        if (n.contains(" institut ") || n.contains(" ecole superieure ") || n.contains(" ecole normale ")
                || n.contains(" grande ecole ") || n.contains(" polytechnique ") || n.contains(" bts ")) return ECOLE_SUPERIEURE;
        if (n.contains(" lycee ")) return LYCEE;
        if (n.contains(" college ") || n.contains(" ceg ") || n.contains(" cem ")) return COLLEGE;
        if ("college".equals(amenity)) return ECOLE_SUPERIEURE;
        return ECOLE;
    }
}
