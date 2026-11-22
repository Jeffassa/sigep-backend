package ci.esatic.sigep.annuaire;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lit l'extraction « Education Facilities » d'OpenStreetMap publiée chaque mois par pays sur HDX
 * (archive zip contenant un GeoJSON). Garde les écoles, collèges, lycées et universités qui portent
 * un nom et se trouvent dans le pays ; écarte les maternelles et les erreurs d'étiquetage.
 */
public final class LecteurEducationFacilities {

    private static final Set<String> GARDES = Set.of("school", "college", "university");
    private static final long TAILLE_MAX = 60L * 1024 * 1024;

    private LecteurEducationFacilities() {
    }

    /** Le GeoJSON contenu dans l'archive ; refuse une archive sans GeoJSON ou démesurée. */
    public static byte[] extraireGeoJson(byte[] zip) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (!e.getName().endsWith(".geojson")) continue;
                byte[] contenu = in.readNBytes((int) TAILLE_MAX + 1);
                if (contenu.length > TAILLE_MAX) throw new IOException("GeoJSON trop volumineux");
                return contenu;
            }
        }
        throw new IOException("Aucun GeoJSON dans l'archive");
    }

    public static List<AnnuaireEtablissement> lire(byte[] geoJson, PaysUemoa pays, ObjectMapper mapper,
                                                   LocalDateTime maintenant) {
        List<AnnuaireEtablissement> fiches = new ArrayList<>();
        Set<String> dejaVus = new HashSet<>();
        for (JsonNode f : mapper.readTree(geoJson).path("features")) {
            JsonNode p = f.path("properties");
            String paysFiche = texte(p, "adm0_pcode");
            if (paysFiche != null && !paysFiche.equalsIgnoreCase(pays.getIso3())) continue;

            String amenity = texte(p, "amenity");
            String batiment = texte(p, "building");
            if (amenity != null ? !GARDES.contains(amenity) : (batiment == null || !GARDES.contains(batiment))) continue;

            String nom = premier(p, "name_fr", "name", "name_latin");
            if (nom == null) continue;
            nom = nom.trim().replaceAll("\\s+", " ");
            if (nom.length() < 3 || nom.length() > 255) continue;

            String nomRecherche = TexteRecherche.normaliser(nom);
            String ville = premier(p, "addr_city", "adm3_name", "adm2_name");
            if (ville != null && ville.length() > 120) ville = ville.substring(0, 120);
            if (!dejaVus.add(nomRecherche + "|" + TexteRecherche.normaliser(ville))) continue;

            double[] centre = centre(f.path("geometry"));
            String id = texte(p, "id");
            fiches.add(AnnuaireEtablissement.builder()
                    .nom(nom)
                    .nomRecherche(nomRecherche)
                    .type(TypeAnnuaire.deduire(amenity != null ? amenity : batiment, nomRecherche))
                    .ville(ville)
                    .paysCode(pays.getCode())
                    .source("OSM")
                    .sourceId(id != null && id.length() <= 40 ? id : pays.getIso3() + "/" + fiches.size())
                    .latitude(centre == null ? null : centre[1])
                    .longitude(centre == null ? null : centre[0])
                    .dateCollecte(maintenant)
                    .build());
        }
        return fiches;
    }

    /** Centre approximatif : le point, ou la moyenne des sommets du contour extérieur. */
    private static double[] centre(JsonNode g) {
        JsonNode c = g.path("coordinates");
        switch (g.path("type").asString("")) {
            case "Point":
                return c.size() >= 2 ? new double[]{c.get(0).asDouble(), c.get(1).asDouble()} : null;
            case "Polygon":
                return moyenne(c.path(0));
            case "MultiPolygon":
                return moyenne(c.path(0).path(0));
            default:
                return null;
        }
    }

    private static double[] moyenne(JsonNode anneau) {
        if (anneau.size() == 0) return null;
        double x = 0, y = 0;
        for (JsonNode pt : anneau) {
            x += pt.get(0).asDouble();
            y += pt.get(1).asDouble();
        }
        return new double[]{x / anneau.size(), y / anneau.size()};
    }

    private static String texte(JsonNode p, String cle) {
        JsonNode v = p.path(cle);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asString("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String premier(JsonNode p, String... cles) {
        for (String c : cles) {
            String v = texte(p, c);
            if (v != null) return v;
        }
        return null;
    }
}
