package ci.esatic.sigep.annuaire;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LecteurListesOfficiellesTest {

    private static final LocalDateTime DATE = LocalDateTime.of(2026, 11, 22, 10, 0);

    static final String PAGE_MESRS = """
            <table><thead><tr><th>#</th><th>ETABLISSEMENT</th><th>VILLE</th><th>COMMUNE</th><th>CAPACITE</th></tr></thead>
            <tbody>
            <tr><td><span>1</span></td><td><span>ABIDJAN BUSINESS SCHOOL -(ABS)</span></td><td><span>ABIDJAN</span></td>
                <td><span>COCODY</span></td><td><span>0</span></td></tr>
            <tr><td><span>2</span></td><td><span>Universit&eacute; Al Fourqane de Cote d&#39;Ivoire -(UFCI)</span></td>
                <td><span>ABIDJAN</span></td><td><span>YOPOUGON</span></td><td><span>0</span></td></tr>
            <tr><td><span>3</span></td><td><span>Aucun Choix</span></td><td></td><td></td><td></td></tr>
            <tr><td><span>4</span></td><td><span>ABIDJAN BUSINESS SCHOOL -(ABS)</span></td><td><span>ABIDJAN</span></td>
                <td><span>COCODY</span></td><td><span>0</span></td></tr>
            </tbody></table>
            """;

    @Test
    void lireMesrs_garderNomVilleCommune_sansLignesVidesNiDoublons() {
        List<EtablissementOfficiel> liste = LecteurListesOfficielles.lireMesrs(PAGE_MESRS,
                SourceOfficielle.MESRS_GRANDES_ECOLES, CategorieOfficielle.GRANDE_ECOLE_PRIVEE, DATE);
        assertThat(liste).extracting(EtablissementOfficiel::getNom)
                .containsExactly("ABIDJAN BUSINESS SCHOOL -(ABS)", "Université Al Fourqane de Cote d'Ivoire -(UFCI)");
        EtablissementOfficiel abs = liste.get(0);
        assertThat(abs.getVille()).isEqualTo("ABIDJAN");
        assertThat(abs.getCommune()).isEqualTo("COCODY");
        assertThat(abs.getNomCle()).isEqualTo("abidjan business school");   // sans le sigle
        assertThat(abs.getNomRecherche()).isEqualTo("abidjan business school abs");
    }

    @Test
    void lireMena_lesCodesEtLeStatutDuClassementDuBepc() throws Exception {
        byte[] pdf = pdf(
                "RANG DRENA COMMUNE CODE ETAB. ETABLISSEMENT TYPE ETAB. INSCRITS PRESENTS ADMIS %TAUX",
                "1 ABIDJAN 1 COCODY 000405 LYCEE SAINTE MARIE ABIDJAN PUBLIC 173 172 172 100",
                "057939 COLLEGE PRIVE CATHOLIQUE PERE MONTI PRIVE",
                "3557 SINFRA SINFRA 070825 COLLEGE PRIVE JOHANNES KEPLER DE SINFRA PRIVE 155 141 8 5,67",
                "2 ABIDJAN 1 COCODY 000405 LYCEE SAINTE MARIE ABIDJAN PUBLIC 10 10 10 100");
        List<EtablissementOfficiel> liste = LecteurListesOfficielles.lireMena(pdf, DATE);
        assertThat(liste).extracting(EtablissementOfficiel::getCodeOfficiel).containsExactly("000405", "057939", "070825");
        assertThat(liste.get(0).getNom()).isEqualTo("LYCEE SAINTE MARIE ABIDJAN");
        assertThat(liste.get(1).getNom()).isEqualTo("COLLEGE PRIVE CATHOLIQUE PERE MONTI");
        assertThat(liste.get(2).getNom()).isEqualTo("COLLEGE PRIVE JOHANNES KEPLER DE SINFRA");
        assertThat(liste.get(0).getCategorie()).isEqualTo(CategorieOfficielle.SECONDAIRE_PUBLIC);
        assertThat(liste.get(1).getCategorie()).isEqualTo(CategorieOfficielle.SECONDAIRE_PRIVE);
    }

    @Test
    void lireSaisie_ignoreLesNotes() throws Exception {
        String csv = "# note\nUNIVERSITE_PUBLIQUE;Université de Man (UMan);Man\n\n";
        List<EtablissementOfficiel> liste = LecteurListesOfficielles.lireSaisie(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), DATE);
        assertThat(liste).hasSize(1);
        assertThat(liste.get(0).getNomCle()).isEqualTo("universite de man");
        assertThat(liste.get(0).getSource()).isEqualTo(SourceOfficielle.SIGEP_PUBLIC);
    }

    @Test
    void laListeDuSuperieurPublic_seLit() throws Exception {
        try (var csv = new org.springframework.core.io.ClassPathResource("officiels/superieur-public.csv").getInputStream()) {
            assertThat(LecteurListesOfficielles.lireSaisie(csv, DATE)).hasSizeGreaterThan(10);
        }
    }

    static byte[] pdf(String... lignes) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream flux = new PDPageContentStream(doc, page)) {
                flux.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 7);
                float y = 750;
                for (String ligne : lignes) {
                    flux.beginText();
                    flux.newLineAtOffset(20, y);
                    flux.showText(ligne);
                    flux.endText();
                    y -= 14;
                }
            }
            doc.save(sortie);
            return sortie.toByteArray();
        }
    }
}
