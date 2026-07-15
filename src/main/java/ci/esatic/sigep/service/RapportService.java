package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.response.RapportResponse;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.repository.*;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class RapportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Value("${app.rapports.dossier:rapports}")
    private String dossierRapports;

    private final EnseignantRepository enseignantRepository;
    private final EmargementRepository emargementRepository;
    private final RapportPdfRepository rapportPdfRepository;
    private final SeanceRepository seanceRepository;
    private final ci.esatic.sigep.tenant.TenantTaskRunner tenantTaskRunner;

    // Génération automatique chaque dimanche à 2h du matin
    @Scheduled(cron = "0 0 2 * * SUN")
    public void genererRapportsHebdomadaires() {
        LocalDate fin = LocalDate.now().with(DayOfWeek.SATURDAY);
        LocalDate debut = fin.with(DayOfWeek.MONDAY);
        log.info("Debut generation rapports hebdomadaires : {} -> {}", debut, fin);

        // Exécuté PAR ÉTABLISSEMENT : la liste d'enseignants est filtrée au tenant et les
        // rapports créés sont estampillés sur le bon établissement.
        tenantTaskRunner.pourChaqueTenantActif(etab -> {
            List<Enseignant> enseignants = enseignantRepository.findAll();
            int nb = 0;
            for (Enseignant enseignant : enseignants) {
                try {
                    genererRapportEnseignant(enseignant, debut, fin, TypeRapport.HEBDOMADAIRE);
                    nb++;
                } catch (Exception e) {
                    log.error("Erreur rapport pour {} : {}", enseignant.getMatricule(), e.getMessage());
                }
            }
            log.info("Rapports hebdo etablissement {} : {}/{}", etab.getId(), nb, enseignants.size());
        });
    }

    @Transactional
    public RapportResponse genererRapportEnseignant(Enseignant enseignant, LocalDate debut,
                                                     LocalDate fin, TypeRapport type) throws Exception {
        List<Emargement> emargements = emargementRepository.findByEnseignantIdAndPeriode(
                enseignant.getId(),
                debut.atStartOfDay(),
                fin.atTime(23, 59, 59));

        // Calculer le total d'heures
        double totalHeures = emargements.stream()
                .mapToDouble(e -> Duration.between(
                        e.getSeance().getHeureDebut().atDate(e.getSeance().getDate()),
                        e.getSeance().getHeureFin().atDate(e.getSeance().getDate())
                ).toMinutes() / 60.0)
                .sum();

        String nomFichier = String.format("Rapport_%s_%s_%s_%s.pdf",
                sanitizeFilenamePart(enseignant.getMatricule()),
                sanitizeFilenamePart(enseignant.getNom()),
                sanitizeFilenamePart(enseignant.getPrenom()),
                libelleSemaine(debut, fin));

        Path dossier = Paths.get(dossierRapports);
        Files.createDirectories(dossier);
        Path cheminFichier = dossier.resolve(nomFichier);

        genererPDF(enseignant, emargements, totalHeures, debut, fin, cheminFichier);

        // Octets relus immédiatement (le fichier existe à coup sûr ici) pour les persister
        // en base : c'est la copie fiable côté téléchargement (disque éphémère sur l'hôte).
        byte[] contenu = Files.readAllBytes(cheminFichier);
        long taille = contenu.length;

        RapportPdf rapport = RapportPdf.builder()
                .enseignant(enseignant)
                .periodeDebut(debut)
                .periodeFin(fin)
                .nomFichier(nomFichier)
                .cheminFichier(cheminFichier.toString())
                .contenuPdf(contenu)
                .tailleFichierOctets(taille)
                .type(type)
                .build();

        rapport = rapportPdfRepository.save(rapport);
        return toResponse(rapport);
    }

    private void genererPDF(Enseignant enseignant, List<Emargement> emargements,
                             double totalHeures, LocalDate debut, LocalDate fin,
                             Path cheminFichier) throws Exception {
        Document document = new Document(PageSize.A4);
        PdfWriter.getInstance(document, new FileOutputStream(cheminFichier.toFile()));
        document.open();

        // Couleurs SIGEP
        Color primary = new Color(0, 6, 102);   // #000666
        Color secondary = new Color(254, 195, 48); // #fec330

        // En-tête
        Font titleFont = new Font(Font.HELVETICA, 20, Font.BOLD, primary);
        Font headerFont = new Font(Font.HELVETICA, 11, Font.BOLD, Color.WHITE);
        Font bodyFont = new Font(Font.HELVETICA, 10, Font.NORMAL, Color.DARK_GRAY);
        Font labelFont = new Font(Font.HELVETICA, 10, Font.BOLD, primary);

        Paragraph titre = new Paragraph("SIGEP - Rapport d'Emargement", titleFont);
        titre.setAlignment(Element.ALIGN_CENTER);
        document.add(titre);
        document.add(new Paragraph("Solutions de Gestion des Enseignements", bodyFont));
        document.add(Chunk.NEWLINE);

        // Infos enseignant
        PdfPTable infoTable = new PdfPTable(2);
        infoTable.setWidthPercentage(100);
        addInfoCell(infoTable, "Enseignant :", enseignant.getPrenom() + " " + enseignant.getNom(), labelFont, bodyFont);
        addInfoCell(infoTable, "Matricule :", enseignant.getMatricule(), labelFont, bodyFont);
        addInfoCell(infoTable, "Departement :", enseignant.getDepartement() != null ? enseignant.getDepartement() : "-", labelFont, bodyFont);
        addInfoCell(infoTable, "Periode :", debut.format(DATE_FMT) + " au " + fin.format(DATE_FMT), labelFont, bodyFont);
        document.add(infoTable);
        document.add(Chunk.NEWLINE);

        // Tableau des séances
        PdfPTable table = new PdfPTable(new float[]{1.8f, 1.2f, 1.2f, 1.6f, 1.6f, 1f, 2f});
        table.setWidthPercentage(100);

        String[] headers = {"Matiere", "Classe", "Salle", "Date", "Horaire", "Heures", "Signature"};
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
            cell.setBackgroundColor(primary);
            cell.setPadding(6);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }

        for (int i = 0; i < emargements.size(); i++) {
            Emargement e = emargements.get(i);
            Seance s = e.getSeance();
            Color bg = (i % 2 == 0) ? Color.WHITE : new Color(242, 244, 246);
            double heures = Duration.between(s.getHeureDebut(), s.getHeureFin()).toMinutes() / 60.0;

            addDataCell(table, s.getMatiere().getLibelle(), bodyFont, bg);
            addDataCell(table, s.getClasse().getLibelle(), bodyFont, bg);
            addDataCell(table, s.getSalle().getLibelle(), bodyFont, bg);
            addDataCell(table, s.getDate().format(DATE_FMT), bodyFont, bg);
            addDataCell(table, s.getHeureDebut() + " - " + s.getHeureFin(), bodyFont, bg);
            addDataCell(table, String.format("%.1fh", heures), bodyFont, bg);
            addSignatureCell(table, e.getSignatureBase64(), bodyFont, bg);
        }
        document.add(table);
        document.add(Chunk.NEWLINE);

        // Total
        Font totalFont = new Font(Font.HELVETICA, 12, Font.BOLD, primary);
        Paragraph total = new Paragraph(String.format("Total heures effectuees : %.1f h", totalHeures), totalFont);
        total.setAlignment(Element.ALIGN_RIGHT);
        document.add(total);

        // Signature
        document.add(Chunk.NEWLINE);
        document.add(new Paragraph("Genere automatiquement par SIGEP le " +
                LocalDate.now().format(DATE_FMT), bodyFont));

        document.close();
    }

    private void addInfoCell(PdfPTable table, String label, String value, Font labelFont, Font bodyFont) {
        PdfPCell l = new PdfPCell(new Phrase(label, labelFont));
        l.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
        l.setPadding(4);
        PdfPCell v = new PdfPCell(new Phrase(value, bodyFont));
        v.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
        v.setPadding(4);
        table.addCell(l);
        table.addCell(v);
    }

    private void addDataCell(PdfPTable table, String value, Font font, Color bg) {
        PdfPCell cell = new PdfPCell(new Phrase(value, font));
        cell.setBackgroundColor(bg);
        cell.setPadding(5);
        table.addCell(cell);
    }

    /** Cellule affichant la signature manuscrite (image PNG Base64) de l'émargement. */
    private void addSignatureCell(PdfPTable table, String signatureBase64, Font font, Color bg) {
        try {
            if (signatureBase64 != null && !signatureBase64.isBlank()) {
                // La signature peut contenir le préfixe "data:image/png;base64,"
                String payload = signatureBase64.contains(",")
                        ? signatureBase64.substring(signatureBase64.indexOf(',') + 1)
                        : signatureBase64;
                byte[] bytes = java.util.Base64.getDecoder().decode(payload.trim());
                Image img = Image.getInstance(bytes);
                img.scaleToFit(80, 34);
                PdfPCell cell = new PdfPCell(img, true);
                cell.setBackgroundColor(bg);
                cell.setPadding(4);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                table.addCell(cell);
                return;
            }
        } catch (Exception ex) {
            log.warn("Signature illisible pour un emargement : {}", ex.getMessage());
        }
        addDataCell(table, "-", font, bg);
    }

    // Génération manuelle pour tous les enseignants sur une période donnée
    @Transactional
    public List<RapportResponse> genererTousRapports(LocalDate debut, LocalDate fin) {
        List<Enseignant> enseignants = enseignantRepository.findAll();
        List<RapportResponse> results = new java.util.ArrayList<>();
        for (Enseignant enseignant : enseignants) {
            try {
                results.add(genererRapportEnseignant(enseignant, debut, fin, TypeRapport.PONCTUEL));
            } catch (Exception e) {
                log.error("Rapport non generé pour {} : {}", enseignant.getMatricule(), e.getMessage());
            }
        }
        log.info("Generation manuelle : {}/{} rapports generes", results.size(), enseignants.size());
        return results;
    }

    public List<RapportResponse> getAllRapports() {
        return rapportPdfRepository.findAllByOrderByDateGenerationDesc()
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    /** Rapports filtrés par enseignant (optionnel) et chevauchement de période (optionnel). */
    public List<RapportResponse> getRapportsFiltres(Long enseignantId, LocalDate debut, LocalDate fin) {
        return rapportPdfRepository.findFiltres(enseignantId, debut, fin)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<RapportPdf> getRapportPdfsFiltres(Long enseignantId, LocalDate debut, LocalDate fin) {
        return rapportPdfRepository.findFiltres(enseignantId, debut, fin);
    }

    /**
     * Synthèse Excel (.xlsx) : une ligne par enseignant sur la période —
     * séances prévues, émargées, taux, heures effectuées, niveau d'assiduité.
     * (Noms POI qualifiés pour éviter le conflit Font/Color avec OpenPDF.)
     */
    public byte[] genererSyntheseExcel(LocalDate debut, LocalDate fin) throws IOException {
        List<Enseignant> enseignants = enseignantRepository.findAll();
        try (org.apache.poi.ss.usermodel.Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("Synthese");
            org.apache.poi.ss.usermodel.CellStyle headerStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            String[] cols = {"Enseignant", "Matricule", "Departement", "Seances prevues",
                    "Emargees", "Taux (%)", "Heures", "Niveau"};
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                org.apache.poi.ss.usermodel.Cell c = header.createCell(i);
                c.setCellValue(cols[i]);
                c.setCellStyle(headerStyle);
            }

            int r = 1;
            for (Enseignant e : enseignants) {
                List<Seance> seances = seanceRepository.findByEnseignantIdAndDateBetween(e.getId(), debut, fin);
                long total = seances.size();
                List<Seance> emargees = seances.stream()
                        .filter(s -> s.getStatut() == StatutSeance.EMARGE).collect(Collectors.toList());
                long nbEmargees = emargees.size();
                double heures = emargees.stream()
                        .mapToDouble(s -> Duration.between(s.getHeureDebut(), s.getHeureFin()).toMinutes() / 60.0).sum();
                double taux = total > 0 ? Math.round(nbEmargees * 1000.0 / total) / 10.0 : 0.0;

                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(e.getPrenom() + " " + e.getNom());
                row.createCell(1).setCellValue(e.getMatricule());
                row.createCell(2).setCellValue(e.getDepartement() != null ? e.getDepartement() : "-");
                row.createCell(3).setCellValue(total);
                row.createCell(4).setCellValue(nbEmargees);
                row.createCell(5).setCellValue(taux);
                row.createCell(6).setCellValue(Math.round(heures * 10) / 10.0);
                row.createCell(7).setCellValue(niveauAssiduite(taux, total));
            }
            for (int i = 0; i < cols.length; i++) sheet.autoSizeColumn(i);

            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Construit une archive ZIP contenant les PDF des rapports fournis. */
    public byte[] genererZip(List<RapportPdf> rapports) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        java.util.Set<String> nomsUtilises = new java.util.HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (RapportPdf r : rapports) {
                byte[] contenu = r.getContenuPdf();
                if (contenu == null) {
                    Path p = Paths.get(r.getCheminFichier());
                    if (!Files.exists(p)) continue; // ni en base ni sur disque : on ignore
                    contenu = Files.readAllBytes(p);
                }
                String nom = nomFichierTelechargement(r);
                String unique = nom;
                int n = 1;
                while (!nomsUtilises.add(unique)) {
                    unique = nom.replaceFirst("\\.pdf$", "_" + (n++) + ".pdf");
                }
                zos.putNextEntry(new ZipEntry(unique));
                zos.write(contenu);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    public byte[] getRapportBytes(Long id) throws IOException {
        RapportPdf rapport = rapportPdfRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Rapport introuvable"));
        // Source fiable : la base. Repli sur le disque pour d'éventuels anciens rapports.
        if (rapport.getContenuPdf() != null) return rapport.getContenuPdf();
        return Files.readAllBytes(Paths.get(rapport.getCheminFichier()));
    }

    public RapportPdf getRapportEntity(Long id) {
        return rapportPdfRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Rapport introuvable"));
    }

    /**
     * Nom de fichier de téléchargement basé sur le matricule et le nom de l'enseignant.
     * Sanitize strict pour éviter toute injection d'en-tête HTTP (CRLF) ou de chemin.
     */
    public String nomFichierTelechargement(RapportPdf rapport) {
        Enseignant e = rapport.getEnseignant();
        // Nom du prof + semaine du rapport (ex. Rapport_DIALLO_Fatou_semaine_09-15062026.pdf)
        return String.format("Rapport_%s_%s_%s.pdf",
                sanitizeFilenamePart(e.getNom()),
                sanitizeFilenamePart(e.getPrenom()),
                libelleSemaine(rapport.getPeriodeDebut(), rapport.getPeriodeFin()));
    }

    /** Libellé de semaine pour les noms de fichier : semaine_jjMMaaaa-jjMMaaaa. */
    private static String libelleSemaine(LocalDate debut, LocalDate fin) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("ddMMyyyy");
        return "semaine_" + debut.format(f) + "-" + fin.format(f);
    }

    private static String sanitizeFilenamePart(String value) {
        if (value == null || value.isBlank()) return "NA";
        // On ne conserve que des caractères sûrs pour un nom de fichier / en-tête HTTP
        String cleaned = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")          // retire les accents
                .replaceAll("[^A-Za-z0-9._-]", "_") // remplace tout le reste
                .replaceAll("_+", "_");
        return cleaned.isBlank() ? "NA" : cleaned;
    }

    public List<RapportPdf> getRapportsByIds(List<Long> ids) {
        return rapportPdfRepository.findAllById(ids);
    }

    private RapportResponse toResponse(RapportPdf r) {
        Long ensId = r.getEnseignant().getId();
        long total = seanceRepository.countByEnseignantIdAndPeriode(ensId, r.getPeriodeDebut(), r.getPeriodeFin());
        long emargees = seanceRepository.countEmargeesParEnseignant(ensId, r.getPeriodeDebut(), r.getPeriodeFin());
        double taux = total > 0 ? Math.round(emargees * 1000.0 / total) / 10.0 : 0.0;
        return RapportResponse.builder()
                .id(r.getId())
                .enseignantNom(r.getEnseignant().getNom())
                .enseignantPrenom(r.getEnseignant().getPrenom())
                .periodeDebut(r.getPeriodeDebut())
                .periodeFin(r.getPeriodeFin())
                .nomFichier(r.getNomFichier())
                .tailleFichierOctets(r.getTailleFichierOctets())
                .dateGeneration(r.getDateGeneration())
                .type(r.getType())
                .tauxEmargement(taux)
                .niveau(niveauAssiduite(taux, total))
                .build();
    }

    /** Classe l'assiduité de l'enseignant sur la période (seuils centralisés — F4). */
    private String niveauAssiduite(double taux, long total) {
        return total == 0 ? "—" : Assiduite.niveau(taux);
    }
}
