package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import com.lowagie.text.Document;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Chaque professeur a son emploi du temps : un fichier, une feuille ou un PDF par personne. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EmploiDuTempsParProfesseurTest {

    private static final LocalDate LUNDI = LocalDate.of(2027, 3, 1);

    @MockitoBean private DataInitializer dataInitializer;

    @Autowired private MockMvc mockMvc;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EtablissementRepository etablissementRepository;
    @Autowired private EnseignantRepository enseignantRepository;
    @Autowired private SeanceRepository seanceRepository;
    @Autowired private MatiereRepository matiereRepository;

    private User admin;
    private Enseignant aya;
    private Enseignant koffi;

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        Etablissement ecole = etablissementRepository.save(Etablissement.builder().nom("Ecole").slug("prof-" + n)
                .plan(Plan.ENTERPRISE).kioskKey("cle-" + n).build());
        Role role = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(null, ERole.ROLE_ADMIN)));
        admin = userRepository.save(User.builder().email("prof-" + n + "@test.local").password("x")
                .roles(new HashSet<>(Set.of(role))).etablissement(ecole).build());
        aya = enseignant("Kouassi", "Aya", ecole, n);
        koffi = enseignant("Yao", "Koffi", ecole, n);
    }

    private Enseignant enseignant(String nom, String prenom, Etablissement ecole, long n) {
        return enseignantRepository.save(Enseignant.builder().matricule(nom + "-" + n).nom(nom).prenom(prenom)
                .statut(StatutEnseignant.VALIDATED).etablissementId(ecole.getId()).build());
    }

    private MockMultipartFile fichier(String nom, byte[] octets) {
        return new MockMultipartFile("fichier", nom, "application/octet-stream", octets);
    }

    private MockMultipartFile csv(String nom, String contenu) {
        return fichier(nom, contenu.getBytes(StandardCharsets.UTF_8));
    }

    private org.springframework.test.web.servlet.ResultActions deposer(MockMultipartFile... fichiers) throws Exception {
        MockMultipartHttpServletRequestBuilder r = multipart("/admin/planning/importer");
        for (MockMultipartFile f : fichiers) r.file(f);
        return mockMvc.perform(r.param("debut", LUNDI.toString()).param("fin", LUNDI.plusDays(6).toString())
                .with(user(admin)).with(csrf()));
    }

    private List<Seance> seancesDe(Enseignant e) {
        return seanceRepository.findByEnseignantIdAndDateBetween(e.getId(), LUNDI, LUNDI.plusDays(6));
    }

    private static final String GRILLE = """
            Créneau;Lundi;Mardi
            08h-10h;Réseaux - L3 Info - B12;
            10h-12h;;Sécurité - Génie logiciel 3 - B14
            """;

    @Test
    void uneLigneEnTete_ditAQuiEstLEmploiDuTemps() throws Exception {
        deposer(csv("edt.csv", "Enseignant : M. Kouassi\n" + GRILLE));

        List<Seance> s = seancesDe(aya);
        assertThat(s).hasSize(2);
        assertThat(s.get(0).getClasse().getLibelle()).isEqualTo("L3 Info");
        assertThat(s.get(0).getSalle().getLibelle()).isEqualTo("B12");
        assertThat(s.get(1).getMatiere().getLibelle()).isEqualTo("Sécurité");
        assertThat(s.get(1).getClasse().getLibelle()).isEqualTo("Génie logiciel 3");
    }

    @Test
    void plusieursFichiers_unParProfesseur_reconnusASonNom() throws Exception {
        deposer(csv("EDT_Kouassi_Aya.csv", GRILLE), csv("edt-yao-koffi.csv", GRILLE));

        assertThat(seancesDe(aya)).hasSize(2);
        assertThat(seancesDe(koffi)).hasSize(2);
    }

    @Test
    void leProfesseurChoisi_prevautPourUnFichierSansNom() throws Exception {
        mockMvc.perform(multipart("/admin/planning/importer").file(csv("edt.csv", GRILLE))
                .param("debut", LUNDI.toString()).param("fin", LUNDI.plusDays(6).toString())
                .param("enseignantId", koffi.getId().toString()).with(user(admin)).with(csrf()));

        assertThat(seancesDe(koffi)).hasSize(2);
    }

    @Test
    void uneFeuilleParProfesseur_dansLeMemeClasseur() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String nom : List.of("Kouassi", "Yao Koffi")) {
                Sheet f = wb.createSheet(nom);
                Row t = f.createRow(0);
                t.createCell(0).setCellValue("Créneau");
                t.createCell(1).setCellValue("Lundi");
                t.createCell(2).setCellValue("Mardi");
                Row l = f.createRow(1);
                l.createCell(0).setCellValue("08h-10h");
                l.createCell(1).setCellValue("Réseaux - L3 Info - B12");
            }
            wb.write(out);
            deposer(fichier("edt.xlsx", out.toByteArray()));
        }

        assertThat(seancesDe(aya)).hasSize(1);
        assertThat(seancesDe(koffi)).hasSize(1);
    }

    @Test
    void uneListeSansColonneEnseignant_vautPourSonTitulaire() throws Exception {
        deposer(csv("edt.csv", """
                Professeur : Koffi Yao
                Jour;Début;Fin;Matière;Classe;Salle
                Lundi;08:00;10:00;Réseaux;L3;B12
                """));

        assertThat(seancesDe(koffi)).hasSize(1);
    }

    @Test
    void unProfesseurAbsentDeLAnnuaire_estNomme_sansBloquerLesAutres() throws Exception {
        deposer(csv("a.csv", "Enseignant : Inconnu Total\n" + GRILLE), csv("EDT_Kouassi.csv", GRILLE))
                .andExpect(flash().attribute("erreursImport", org.hamcrest.Matchers.hasItem(containsString("Inconnu Total"))));

        assertThat(seancesDe(aya)).hasSize(2);
    }

    @Test
    void unDocumentWord_seLitComeUnTableur() throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Enseignant : Aya Kouassi");
            XWPFTable t = doc.createTable(2, 3);
            String[][] c = {{"Créneau", "Lundi", "Mardi"}, {"08h-10h", "Réseaux - L3 Info - B12", ""}};
            for (int i = 0; i < 2; i++) for (int j = 0; j < 3; j++) t.getRow(i).getCell(j).setText(c[i][j]);
            doc.write(out);
            deposer(fichier("edt.docx", out.toByteArray()));
        }

        assertThat(seancesDe(aya)).hasSize(1);
    }

    private byte[] pdf() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document d = new Document();
        PdfWriter.getInstance(d, out);
        d.open();
        d.add(new Phrase("Enseignant : Aya Kouassi\n\n"));
        PdfPTable t = new PdfPTable(3);
        for (String c : List.of("Horaire", "Lundi", "Mardi", "08:00 - 10:00", "Reseaux - L3 Info - B12", "", "10:00 - 12:00", "", "Securite - L2 Info - B14")) {
            t.addCell(c);
        }
        d.add(t);
        d.close();
        return out.toByteArray();
    }

    @Test
    void unPdf_seLitSurNosServeurs_etAttendLaRelecture() throws Exception {
        long matieres = matiereRepository.count();

        deposer(fichier("edt.pdf", pdf())).andExpect(status().isOk())
                .andExpect(view().name("admin/planning-relecture"))
                .andExpect(content().string(containsString("Reseaux")))
                .andExpect(content().string(containsString("B14")));

        assertThat(seancesDe(aya)).isEmpty();
        assertThat(matiereRepository.count()).isEqualTo(matieres);
    }

    @Test
    void lesSeancesRelues_sEnregistrent() throws Exception {
        String json = "[{\"date\":\"" + LUNDI + "\",\"debut\":\"08:00\",\"fin\":\"10:00\",\"matiere\":\"Réseaux\","
                + "\"enseignantId\":" + aya.getId() + ",\"classe\":\"L3\",\"salle\":\"B12\"},"
                + "{\"date\":\"" + LUNDI + "\",\"debut\":\"10:00\",\"fin\":\"12:00\",\"matiere\":\"Algo\","
                + "\"enseignantId\":null,\"classe\":\"L3\",\"salle\":\"B12\"}]";

        mockMvc.perform(post("/admin/planning/confirmer").param("lignes", json).with(user(admin)).with(csrf()))
                .andExpect(flash().attribute("erreursImport", List.of("Ligne 2 : choisissez l'enseignant")));

        assertThat(seancesDe(aya)).hasSize(1);
    }

    @Test
    void unePhoto_estRefuseeSansQuitterLeServeur() throws Exception {
        deposer(fichier("edt.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0}))
                .andExpect(flash().attribute("erreursImport", org.hamcrest.Matchers.hasItem(containsString("format non pris en charge"))));
    }
}
