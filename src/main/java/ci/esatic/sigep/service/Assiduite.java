package ci.esatic.sigep.service;

/**
 * Classification unique de l'assiduité par taux d'émargement (F4).
 * Source unique pour StatsService et RapportService (évite la divergence des seuils).
 */
final class Assiduite {

    static final double SEUIL_EXCELLENT = 90.0;
    static final double SEUIL_BON = 75.0;
    static final double SEUIL_MOYEN = 50.0;

    private Assiduite() {}

    static String niveau(double taux) {
        if (taux >= SEUIL_EXCELLENT) return "Excellent";
        if (taux >= SEUIL_BON) return "Bon";
        if (taux >= SEUIL_MOYEN) return "Moyen";
        return "Faible";
    }
}
