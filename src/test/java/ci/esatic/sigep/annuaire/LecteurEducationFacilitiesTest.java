package ci.esatic.sigep.annuaire;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lecture de l'extraction « Education Facilities » d'OpenStreetMap. */
class LecteurEducationFacilitiesTest {

    static final String GEOJSON = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-3.98,5.34]},
               "properties":{"id":"node/1","name":"Université Félix Houphouët-Boigny","amenity":"university","adm0_pcode":"CIV","adm3_name":"Cocody"}},
              {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[0,0],[2,0],[2,2],[0,2]]]},
               "properties":{"id":"way/2","name":"Lycée Classique d'Abidjan","amenity":"school","adm0_pcode":"CIV","adm2_name":"Abidjan"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[1,1]},
               "properties":{"id":"node/3","name":"Maternelle Les Petits","amenity":"kindergarten","adm0_pcode":"CIV"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[1,1]},
               "properties":{"id":"node/4","amenity":"school","adm0_pcode":"CIV"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[1,1]},
               "properties":{"id":"node/5","name":"Lycée de Bamako","amenity":"school","adm0_pcode":"MLI"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[1,1]},
               "properties":{"id":"node/6","name":"Toilettes du marché","amenity":"toilets","adm0_pcode":"CIV"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-3.98,5.34]},
               "properties":{"id":"way/7","name":"Université Félix Houphouët-Boigny","building":"university","adm0_pcode":"CIV","adm3_name":"Cocody"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[1,1]},
               "properties":{"id":"way/8","name":"EPP Ourone","building":"school","adm0_pcode":"CIV"}}
            ]}""";

    static byte[] zip(String geojson) throws IOException {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(sortie)) {
            z.putNextEntry(new ZipEntry("README.txt"));
            z.write("oex export".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("education_facilities.geojson"));
            z.write(geojson.getBytes(StandardCharsets.UTF_8));
        }
        return sortie.toByteArray();
    }

    @Test
    void gardeLesEcolesNommeesDuPays_etEcarteLeReste() throws IOException {
        byte[] geo = LecteurEducationFacilities.extraireGeoJson(zip(GEOJSON));
        List<AnnuaireEtablissement> fiches = LecteurEducationFacilities.lire(geo, PaysUemoa.CI, JsonMapper.builder().build(),
                LocalDateTime.now());

        // L'université (une fois : le doublon même nom, même ville est écarté), le lycée et l'EPP.
        assertThat(fiches).extracting(AnnuaireEtablissement::getNom)
                .containsExactly("Université Félix Houphouët-Boigny", "Lycée Classique d'Abidjan", "EPP Ourone");
        AnnuaireEtablissement u = fiches.get(0);
        assertThat(u.getType()).isEqualTo(TypeAnnuaire.UNIVERSITE);
        assertThat(u.getVille()).isEqualTo("Cocody");
        assertThat(u.getPaysCode()).isEqualTo("CI");
        assertThat(u.getSourceId()).isEqualTo("node/1");
        assertThat(u.getNomRecherche()).isEqualTo("universite felix houphouet boigny");
        assertThat(u.getLatitude()).isEqualTo(5.34);

        AnnuaireEtablissement lycee = fiches.get(1);
        assertThat(lycee.getType()).isEqualTo(TypeAnnuaire.LYCEE);
        assertThat(lycee.getVille()).isEqualTo("Abidjan");
        assertThat(lycee.getLongitude()).isEqualTo(1.0);   // moyenne du contour
    }

    @Test
    void uneArchiveSansGeoJson_estRefusee() throws IOException {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(sortie)) {
            z.putNextEntry(new ZipEntry("README.txt"));
            z.write(1);
        }
        assertThatThrownBy(() -> LecteurEducationFacilities.extraireGeoJson(sortie.toByteArray()))
                .hasMessageContaining("GeoJSON");
    }

    @Test
    void leTypeSeDeduitDesEtiquettesEtDuNom() {
        assertThat(TypeAnnuaire.deduire("school", TexteRecherche.normaliser("Institut Supérieur de Gestion")))
                .isEqualTo(TypeAnnuaire.ECOLE_SUPERIEURE);
        assertThat(TypeAnnuaire.deduire("school", TexteRecherche.normaliser("Collège Moderne Charles Koffi Diby")))
                .isEqualTo(TypeAnnuaire.COLLEGE);
        assertThat(TypeAnnuaire.deduire("college", TexteRecherche.normaliser("Groupe École des Hautes Études Commerciales")))
                .isEqualTo(TypeAnnuaire.ECOLE_SUPERIEURE);
        assertThat(TypeAnnuaire.deduire("school", TexteRecherche.normaliser("Groupe Scolaire Boni")))
                .isEqualTo(TypeAnnuaire.ECOLE);
    }
}
