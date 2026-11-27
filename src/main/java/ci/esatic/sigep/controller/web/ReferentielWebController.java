package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Classe;
import ci.esatic.sigep.entity.Matiere;
import ci.esatic.sigep.entity.Salle;
import ci.esatic.sigep.repository.ClasseRepository;
import ci.esatic.sigep.repository.MatiereRepository;
import ci.esatic.sigep.repository.SalleRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.service.ReferentielService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Espace admin : gestion des référentiels (matières, classes, salles) + modèle d'emploi du temps. */
@Controller
@RequiredArgsConstructor
public class ReferentielWebController {

    private final MatiereRepository matiereRepository;
    private final ClasseRepository classeRepository;
    private final SalleRepository salleRepository;
    private final ci.esatic.sigep.repository.CampusRepository campusRepository;
    private final ci.esatic.sigep.tenant.plan.PlanService planService;
    private final EtablissementCourantService etablissementCourantService;
    private final EtablissementRepository etablissementRepository;
    private final ci.esatic.sigep.service.ImportService importService;
    private final ci.esatic.sigep.service.CalendrierService calendrierService;
    private final ReferentielService referentielService;
    private final ci.esatic.sigep.repository.EnseignantRepository enseignantRepository;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @GetMapping("/admin/referentiels")
    public String referentiels(Model model) {
        model.addAttribute("matieres", matiereRepository.findAll());
        model.addAttribute("classes", classeRepository.findAll());
        model.addAttribute("salles", salleRepository.findAll());
        model.addAttribute("etablissementCourant", etablissementCourantService.courant());
        model.addAttribute("campusListe", campusRepository.findAllByOrderByNomAsc());
        model.addAttribute("multiCampus", planService.estDisponible(
                etablissementCourantService.courant(),
                ci.esatic.sigep.tenant.plan.Feature.MULTI_CAMPUS));
        return "admin/referentiels";
    }

    @PostMapping("/admin/planning/format")
    public String modifierFormatPlanning(@RequestParam String format, RedirectAttributes ra) {
        try {
            referentielService.changerFormatPlanning(format);
            ra.addFlashAttribute("success", "Format d'emploi du temps mis à jour.");
        } catch (ci.esatic.sigep.exception.MetierException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/referentiels";
    }

    // --- Matières, classes, sites, salles : règles dans ReferentielService (partagées avec l'agent) ---
    @PostMapping("/admin/matieres")
    public String creerMatiere(@RequestParam String libelle,
                               @RequestParam(required = false) String description, RedirectAttributes ra) {
        return agir(ra, () -> "Matière « " + referentielService.creerMatiere(libelle, description).getLibelle() + " » ajoutée.");
    }

    @PostMapping("/admin/matieres/{id}/supprimer")
    public String supprimerMatiere(@PathVariable Long id, RedirectAttributes ra) {
        return agir(ra, () -> { referentielService.supprimer(ReferentielService.Type.MATIERE, id); return "Matière supprimée."; });
    }

    @PostMapping("/admin/classes")
    public String creerClasse(@RequestParam String libelle,
                              @RequestParam(required = false) String filiere,
                              @RequestParam(required = false) Integer niveau, RedirectAttributes ra) {
        return agir(ra, () -> "Classe « " + referentielService.creerClasse(libelle, filiere, niveau).getLibelle() + " » ajoutée.");
    }

    @PostMapping("/admin/classes/{id}/supprimer")
    public String supprimerClasse(@PathVariable Long id, RedirectAttributes ra) {
        return agir(ra, () -> { referentielService.supprimer(ReferentielService.Type.CLASSE, id); return "Classe supprimée."; });
    }

    @PostMapping("/admin/campus")
    public String creerCampus(@RequestParam String nom,
                              @RequestParam(required = false) String adresse,
                              RedirectAttributes ra) {
        return agir(ra, () -> "Établissement « " + referentielService.creerCampus(nom, adresse).getNom() + " » ajouté.");
    }

    @PostMapping("/admin/campus/{id}/supprimer")
    public String supprimerCampus(@PathVariable Long id, RedirectAttributes ra) {
        return agir(ra, () -> { referentielService.supprimer(ReferentielService.Type.CAMPUS, id); return "Établissement supprimé."; });
    }

    @PostMapping("/admin/salles")
    public String creerSalle(@RequestParam String libelle, @RequestParam(required = false) String batiment,
                             @RequestParam(required = false) Integer capacite,
                             @RequestParam(required = false) Long campusId, RedirectAttributes ra) {
        return agir(ra, () -> "Salle « " + referentielService.creerSalle(libelle, batiment, capacite, campusId).getLibelle() + " » ajoutée.");
    }

    @PostMapping("/admin/salles/{id}/supprimer")
    public String supprimerSalle(@PathVariable Long id, RedirectAttributes ra) {
        return agir(ra, () -> { referentielService.supprimer(ReferentielService.Type.SALLE, id); return "Salle supprimée."; });
    }

    /** Exécute l'action et range son message, ou le refus, pour la page des référentiels. */
    private String agir(RedirectAttributes ra, java.util.function.Supplier<String> action) {
        try {
            ra.addFlashAttribute("success", action.get());
        } catch (ci.esatic.sigep.exception.MetierException | ci.esatic.sigep.tenant.plan.PlanLimiteException e) {
            ra.addFlashAttribute("error", e.getMessage());
        } catch (ci.esatic.sigep.exception.ResourceNotFoundException | ci.esatic.sigep.tenant.AccesTenantRefuseException e) {
            ra.addFlashAttribute("error", "Élément introuvable.");
        }
        return "redirect:/admin/referentiels";
    }

    // --- Dépôt de l'emploi du temps ---
    // Tout se lit ici, sur nos serveurs : aucun document ne part chez un tiers.
    @PostMapping("/admin/planning/importer")
    public String importerEmploiDuTemps(
            @RequestParam("fichier") java.util.List<org.springframework.web.multipart.MultipartFile> fichiers,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate fin,
            @RequestParam(required = false) String classe,
            @RequestParam(required = false) Long enseignantId,
            Model model,
            RedirectAttributes ra) {
        // Sans période indiquée, les cours répétés valent pour ce qui reste du semestre en cours :
        // des séances créées dans le passé compteraient aussitôt comme des oublis.
        if (debut == null && fin == null) {
            var semestre = calendrierService.semestreDe(LocalDate.now()).orElse(null);
            if (semestre != null) {
                debut = LocalDate.now();
                fin = semestre.getFin();
            }
        }
        java.util.List<org.springframework.web.multipart.MultipartFile> deposes = fichiers.stream().filter(f -> !f.isEmpty()).toList();
        if (deposes.isEmpty()) {
            ra.addFlashAttribute("error", "Déposez votre emploi du temps.");
            return "redirect:/admin/rapports";
        }
        int importees = 0, doublons = 0, fermes = 0;
        java.util.List<String> erreurs = new java.util.ArrayList<>();
        java.util.List<String> nouveaux = new java.util.ArrayList<>();
        java.util.List<ci.esatic.sigep.service.ImportService.LigneRelue> aRelire = new java.util.ArrayList<>();
        for (org.springframework.web.multipart.MultipartFile fichier : deposes) {
            String nom = fichier.getOriginalFilename() == null ? "" : fichier.getOriginalFilename();
            String minuscule = nom.toLowerCase();
            String prefixe = deposes.size() > 1 ? nom + " : " : "";
            try {
                if (minuscule.endsWith(".pdf")) {
                    var lu = importService.lirePdf(fichier, debut, fin, classe, enseignantId);
                    aRelire.addAll(lu.lignes());
                    lu.erreurs().forEach(e -> erreurs.add(prefixe + e));
                } else if (minuscule.endsWith(".xlsx") || minuscule.endsWith(".xls") || minuscule.endsWith(".csv") || minuscule.endsWith(".docx")) {
                    java.util.Map<String, Object> r = importService.importerPlanning(fichier, debut, fin, classe, enseignantId);
                    importees += (Integer) r.get("totalImporte");
                    doublons += (Integer) r.get("doublonsIgnores");
                    fermes += (Integer) r.get("joursFermesIgnores");
                    @SuppressWarnings("unchecked")
                    java.util.List<String> lignes = (java.util.List<String>) r.get("erreurs");
                    lignes.forEach(e -> erreurs.add(prefixe + e));
                    @SuppressWarnings("unchecked")
                    java.util.List<String> ajoutes = (java.util.List<String>) r.get("nouveauxReferentiels");
                    ajoutes.stream().filter(n -> !nouveaux.contains(n)).forEach(nouveaux::add);
                } else {
                    erreurs.add(prefixe + "format non pris en charge. Déposez un fichier Excel, CSV, Word (.docx) ou un PDF non scanné.");
                }
            } catch (IllegalArgumentException e) {
                erreurs.add(prefixe + e.getMessage());
            } catch (Exception e) {
                erreurs.add(prefixe + "ce fichier n'a pas pu être lu. Vérifiez qu'il s'ouvre bien.");
            }
        }
        String message = importees + " séance(s) importée(s)"
                + (doublons > 0 ? ", " + doublons + " déjà présente(s) ignorée(s)" : "")
                + (fermes > 0 ? ", " + fermes + " date(s) sautée(s) : établissement fermé" : "")
                + (!erreurs.isEmpty() ? ", " + erreurs.size() + " point(s) à corriger" : "") + "."
                + ci.esatic.sigep.service.ImportService.avisNouveauxReferentiels(nouveaux);
        if (!aRelire.isEmpty()) {
            model.addAttribute("lignes", aRelire);
            model.addAttribute("erreursImport", erreurs);
            if (importees > 0) model.addAttribute("success", message);
            model.addAttribute("enseignants", enseignantRepository.findByStatutOrderByNomAsc(ci.esatic.sigep.entity.StatutEnseignant.VALIDATED));
            return "admin/planning-relecture";
        }
        ra.addFlashAttribute(importees > 0 || erreurs.isEmpty() ? "success" : "error", message);
        ra.addFlashAttribute("erreursImport", erreurs);
        return "redirect:/admin/rapports";
    }

    private static final int LIGNES_RELUES_MAX = 5000;

    @PostMapping("/admin/planning/confirmer")
    public String confirmerImport(@RequestParam String lignes, RedirectAttributes ra) {
        java.util.List<ci.esatic.sigep.service.ImportService.LigneRelue> relues;
        try {
            relues = java.util.List.of(objectMapper.readValue(lignes, ci.esatic.sigep.service.ImportService.LigneRelue[].class));
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Ces séances n'ont pas pu être lues. Recommencez l'import.");
            return "redirect:/admin/rapports";
        }
        if (relues.size() > LIGNES_RELUES_MAX) {
            ra.addFlashAttribute("error", "Trop de séances pour un seul import : découpez le document.");
            return "redirect:/admin/rapports";
        }
        var resultat = importService.importerRelues(relues);
        int importees = (Integer) resultat.get("totalImporte");
        int doublons = (Integer) resultat.get("doublonsIgnores");
        int enErreur = (Integer) resultat.get("lignesEnErreur");
        ra.addFlashAttribute(importees > 0 || enErreur == 0 ? "success" : "error", importees + " séance(s) importée(s)"
                + (doublons > 0 ? ", " + doublons + " déjà présente(s) ignorée(s)" : "")
                + (enErreur > 0 ? ", " + enErreur + " ligne(s) à corriger" : "") + ".");
        ra.addFlashAttribute("erreursImport", resultat.get("erreurs"));
        return "redirect:/admin/rapports";
    }

    // --- Modèle d'emploi du temps (CSV téléchargeable) ---
    @GetMapping("/admin/planning/modele")
    public ResponseEntity<byte[]> modeleEmploiDuTemps() {
        String m  = matiereRepository.findAll().stream().findFirst().map(Matiere::getLibelle).orElse("MATIERE");
        String cl = classeRepository.findAll().stream().findFirst().map(Classe::getLibelle).orElse("CLASSE");
        String sa = salleRepository.findAll().stream().findFirst().map(Salle::getLibelle).orElse("SALLE");
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        StringBuilder sb = new StringBuilder();
        String format = etablissementCourantService.courant() == null ? "STANDARD"
            : etablissementCourantService.courant().getFormatEmploiDuTemps();
        String enseignant = "MATRICULE_ENSEIGNANT";
        String matiere = "MATIERE";
        String classe = "CLASSE";
        String salle = "SALLE";
        if ("UNIVERSITAIRE".equals(format)) {
            matiere = "UNITE_ENSEIGNEMENT";
            classe = "NIVEAU_GROUPE";
            salle = "AMPHITHEATRE_SALLE";
        } else if ("SECONDAIRE".equals(format)) {
            enseignant = "PROFESSEUR_MATRICULE";
            matiere = "DISCIPLINE";
            classe = "CLASSE_NIVEAU";
            salle = "SALLE";
        }
        sb.append("DATE;HEURE_DEBUT;HEURE_FIN;").append(enseignant).append(';')
            .append(matiere).append(';').append(classe).append(';').append(salle).append("\r\n");
        sb.append(today).append(";08:00;10:00;MATRICULE;").append(m).append(';').append(cl).append(';').append(sa).append("\r\n");
        sb.append(today).append(";10:15;12:15;MATRICULE;").append(m).append(';').append(cl).append(';').append(sa).append("\r\n");

        // BOM UTF-8 pour qu'Excel ouvre correctement les accents
        byte[] bytes = ("﻿" + sb).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"modele_emploi_du_temps.csv\"")
                .body(bytes);
    }
}
