package ci.esatic.sigep.annuaire;

/** Les listes officielles recopiées dans SIGEP, et l'autorité qui les publie. */
public enum SourceOfficielle {
    MESRS_UNIVERSITES("Universités privées autorisées",
            "Ministère de l'Enseignement supérieur et de la Recherche scientifique"),
    MESRS_GRANDES_ECOLES("Grandes écoles privées autorisées",
            "Ministère de l'Enseignement supérieur et de la Recherche scientifique"),
    MENA_BEPC("Collèges et lycées présentant des candidats au BEPC",
            "Ministère de l'Éducation nationale et de l'Alphabétisation, Direction des examens et concours"),
    SIGEP_PUBLIC("Universités et grandes écoles publiques",
            "Saisie SIGEP, d'après les textes de création");

    private final String libelle;
    private final String organisme;

    SourceOfficielle(String libelle, String organisme) {
        this.libelle = libelle;
        this.organisme = organisme;
    }

    public String getLibelle() { return libelle; }

    public String getOrganisme() { return organisme; }
}
