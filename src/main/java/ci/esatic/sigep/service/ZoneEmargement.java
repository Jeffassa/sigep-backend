package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;

/**
 * Contrôle de position à l'émargement.
 *
 * <p>Le QR affiché en salle prouve qu'on l'a vu, pas qu'on était dans la salle : photographié et
 * envoyé à un collègue absent, il reste utilisable quelques secondes. L'établissement qui le
 * souhaite définit donc une zone (un centre et un rayon) ; le téléphone envoie sa position avec
 * l'émargement, et un émargement venu d'ailleurs est refusé.
 *
 * <p>Seule la distance au centre est calculée, puis oubliée : les coordonnées de l'enseignant ne
 * sont jamais enregistrées. En cas de refus, le journal ne retient que la distance arrondie.
 */
public final class ZoneEmargement {

    public static final int RAYON_DEFAUT = 300;
    public static final int RAYON_MIN = 50;
    public static final int RAYON_MAX = 2_000;

    /**
     * Le téléphone indique la précision de sa position. On lui accorde cette marge, plafonnée :
     * dans un bâtiment, la localisation se trompe facilement de plusieurs dizaines de mètres.
     */
    static final double MARGE_MAX = 150;

    /** Au-delà, la position ne dit plus rien d'utile (localisation par antenne seulement). */
    static final double PRECISION_MAX = 1_000;

    private static final double RAYON_TERRE = 6_371_000;

    private ZoneEmargement() {}

    /** Ce que le téléphone a mesuré au moment d'émarger. Tout peut manquer. */
    public record Position(Double latitude, Double longitude, Double precisionMetres, Boolean simulee) {
        public boolean presente() {
            return latitude != null && longitude != null;
        }
    }

    /** Verdict du contrôle. */
    public enum Verdict { SANS_CONTROLE, ACCEPTE, ABSENTE, SIMULEE, IMPRECISE, HORS_ZONE }

    public record Resultat(Verdict verdict, long distanceMetres) {}

    public static Resultat verifier(Etablissement etab, Position position) {
        if (etab == null || !etab.isZoneDefinie()) return new Resultat(Verdict.SANS_CONTROLE, 0);
        if (position == null || !position.presente()) return new Resultat(Verdict.ABSENTE, 0);
        if (Boolean.TRUE.equals(position.simulee())) return new Resultat(Verdict.SIMULEE, 0);

        double distance = distanceMetres(etab.getZoneLatitude(), etab.getZoneLongitude(),
                position.latitude(), position.longitude());
        long arrondie = Math.round(distance);
        double precision = position.precisionMetres() == null ? 0 : Math.max(0, position.precisionMetres());
        if (precision > PRECISION_MAX) return new Resultat(Verdict.IMPRECISE, arrondie);

        double marge = Math.min(precision, MARGE_MAX);
        return distance - marge <= etab.getZoneRayonMetres()
                ? new Resultat(Verdict.ACCEPTE, arrondie)
                : new Resultat(Verdict.HORS_ZONE, arrondie);
    }

    /** Distance à vol d'oiseau entre deux points (formule de haversine), en mètres. */
    public static double distanceMetres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * RAYON_TERRE * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /**
     * Lit des coordonnées saisies dans la console, telles que les donne une carte :
     * « 5.35995, -4.00826 ». Lève {@link IllegalArgumentException} si elles sont illisibles.
     */
    public static double[] lireCoordonnees(String saisie) {
        if (saisie == null) throw new IllegalArgumentException("coordonnées vides");
        String[] parties = saisie.trim().split("\\s*[,;]\\s*|\\s+");
        if (parties.length != 2) throw new IllegalArgumentException("deux nombres attendus");
        try {
            double lat = Double.parseDouble(parties[0]);
            double lon = Double.parseDouble(parties[1]);
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180 || Double.isNaN(lat) || Double.isNaN(lon)) {
                throw new IllegalArgumentException("hors des bornes");
            }
            return new double[]{lat, lon};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("nombres illisibles");
        }
    }
}
