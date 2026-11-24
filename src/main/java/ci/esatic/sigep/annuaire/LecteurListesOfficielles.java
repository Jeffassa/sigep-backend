package ci.esatic.sigep.annuaire;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.util.HtmlUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lit les listes officielles telles que l'État les publie : pages web du MESRS, PDF du MENA. */
public final class LecteurListesOfficielles {

    private static final Pattern LIGNE = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CELLULE = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern BALISE = Pattern.compile("<[^>]+>");

    /**
     * Une ligne du classement du BEPC : code à six chiffres, nom, puis PUBLIC ou PRIVE suivi des
     * effectifs ou de la fin de ligne. Le nom peut lui-même contenir « PRIVE » (« COLLEGE PRIVE … »).
     */
    private static final Pattern ETABLISSEMENT_BEPC =
            Pattern.compile("(?<!\\d)(\\d{6})\\s+(\\S.*)\\s+(PUBLIC|PRIVE)(?=\\s+\\d|\\s*$)");

    private LecteurListesOfficielles() {
    }

    /**
     * Tableau « Liste des établissements privés d'enseignement autorisés » du portail d'orientation
     * du MESRS : n°, établissement, ville, commune, puis capacité, frais et filières.
     */
    public static List<EtablissementOfficiel> lireMesrs(String html, SourceOfficielle source,
                                                        CategorieOfficielle categorie, LocalDateTime date) {
        Map<String, EtablissementOfficiel> parCle = new LinkedHashMap<>();
        Matcher ligne = LIGNE.matcher(html);
        while (ligne.find()) {
            List<String> cellules = new ArrayList<>();
            Matcher cellule = CELLULE.matcher(ligne.group(1));
            while (cellule.find()) cellules.add(texte(cellule.group(1)));
            if (cellules.size() < 4 || !cellules.get(0).matches("\\d+")) continue;
            String nom = cellules.get(1);
            String cle = TexteRecherche.cle(nom);
            if (cle.length() < 3 || cle.equals("aucun choix")) continue;
            parCle.putIfAbsent(cle + "|" + TexteRecherche.normaliser(cellules.get(3)),
                    EtablissementOfficiel.de(source, categorie, null, nom, cellules.get(2), cellules.get(3), date));
        }
        return new ArrayList<>(parCle.values());
    }

    /**
     * Classement des établissements au BEPC, publié en PDF par la Direction des examens et concours :
     * chaque établissement qui présente des candidats y figure avec son code et son statut.
     */
    public static List<EtablissementOfficiel> lireMena(byte[] pdf, LocalDateTime date) throws IOException {
        String texte;
        try (PDDocument document = ci.esatic.sigep.service.PdfLecture.ouvrir(pdf)) {
            PDFTextStripper lecteur = new PDFTextStripper();
            lecteur.setSortByPosition(true);
            texte = lecteur.getText(document);
        }
        Map<String, EtablissementOfficiel> parCode = new LinkedHashMap<>();
        for (String ligne : texte.split("\\R")) {
            Matcher m = ETABLISSEMENT_BEPC.matcher(ligne.strip());
            if (m.find()) {
                CategorieOfficielle categorie = "PUBLIC".equals(m.group(3))
                        ? CategorieOfficielle.SECONDAIRE_PUBLIC : CategorieOfficielle.SECONDAIRE_PRIVE;
                parCode.putIfAbsent(m.group(1), EtablissementOfficiel.de(SourceOfficielle.MENA_BEPC, categorie,
                        m.group(1), m.group(2), null, null, date));
            }
        }
        return new ArrayList<>(parCode.values());
    }

    /** Liste saisie par SIGEP : une ligne « catégorie;nom;ville », les lignes en # sont des notes. */
    public static List<EtablissementOfficiel> lireSaisie(InputStream csv, LocalDateTime date) throws IOException {
        List<EtablissementOfficiel> liste = new ArrayList<>();
        try (BufferedReader lecteur = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String ligne;
            while ((ligne = lecteur.readLine()) != null) {
                if (ligne.isBlank() || ligne.startsWith("#")) continue;
                String[] champs = ligne.split(";", -1);
                if (champs.length < 3) continue;
                liste.add(EtablissementOfficiel.de(SourceOfficielle.SIGEP_PUBLIC,
                        CategorieOfficielle.valueOf(champs[0].strip()), null, champs[1], champs[2], null, date));
            }
        }
        return liste;
    }

    private static String texte(String html) {
        return HtmlUtils.htmlUnescape(BALISE.matcher(html).replaceAll(" ")).replace(' ', ' ')
                .replaceAll("\\s+", " ").strip();
    }
}
