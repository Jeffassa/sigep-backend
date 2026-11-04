package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Classe;
import ci.esatic.sigep.entity.Matiere;
import ci.esatic.sigep.entity.Salle;
import ci.esatic.sigep.repository.ClasseRepository;
import ci.esatic.sigep.repository.MatiereRepository;
import ci.esatic.sigep.repository.SalleRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.service.EtablissementCourantService;
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
    private final ci.esatic.sigep.service.PlanningIaService planningIaService;
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
        var etab = etablissementCourantService.courant();
        String valeur = format == null ? "STANDARD" : format.trim().toUpperCase();
        if (etab == null || !java.util.Set.of("STANDARD", "UNIVERSITAIRE", "SECONDAIRE").contains(valeur)) {
            ra.addFlashAttribute("error", "Format d'emploi du temps invalide.");
        } else {
            etab.setFormatEmploiDuTemps(valeur);
            // Le tenant est relu et géré par le contexte admin ; save explicite pour persister la préférence.
            etablissementRepository.save(etab);
            ra.addFlashAttribute("success", "Format d'emploi du temps mis à jour.");
        }
        return "redirect:/admin/referentiels";
    }

    // --- Matières ---
    @PostMapping("/admin/matieres")
    public String creerMatiere(@RequestParam String libelle,
                               @RequestParam(required = false) String description, RedirectAttributes ra) {
        String lib = libelle == null ? "" : libelle.trim();
        if (lib.isEmpty()) {
            ra.addFlashAttribute("error", "Le libellé de la matière est obligatoire.");
        } else if (matiereRepository.existsByLibelleIgnoreCase(lib)) {
            ra.addFlashAttribute("error", "Cette matière existe déjà : " + lib);
        } else {
            matiereRepository.save(Matiere.builder().libelle(lib)
                    .description(description != null ? description.trim() : null).build());
            ra.addFlashAttribute("success", "Matière « " + lib + " » ajoutée.");
        }
        return "redirect:/admin/referentiels";
    }

    @PostMapping("/admin/matieres/{id}/supprimer")
    public String supprimerMatiere(@PathVariable Long id, RedirectAttributes ra) {
        try {
            matiereRepository.deleteById(id);
            ra.addFlashAttribute("success", "Matière supprimée.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Suppression impossible : cette matière est utilisée par des séances.");
        }
        return "redirect:/admin/referentiels";
    }

    // --- Classes ---
    @PostMapping("/admin/classes")
    public String creerClasse(@RequestParam String libelle,
                              @RequestParam(required = false) String filiere,
                              @RequestParam(required = false) Integer niveau, RedirectAttributes ra) {
        String lib = libelle == null ? "" : libelle.trim();
        if (lib.isEmpty()) {
            ra.addFlashAttribute("error", "Le libellé de la classe est obligatoire.");
        } else if (classeRepository.existsByLibelleIgnoreCase(lib)) {
            ra.addFlashAttribute("error", "Cette classe existe déjà : " + lib);
        } else {
            classeRepository.save(Classe.builder().libelle(lib)
                    .filiere(filiere != null ? filiere.trim() : null).niveau(niveau).build());
            ra.addFlashAttribute("success", "Classe « " + lib + " » ajoutée.");
        }
        return "redirect:/admin/referentiels";
    }

    @PostMapping("/admin/classes/{id}/supprimer")
    public String supprimerClasse(@PathVariable Long id, RedirectAttributes ra) {
        try {
            classeRepository.deleteById(id);
            ra.addFlashAttribute("success", "Classe supprimée.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Suppression impossible : cette classe est utilisée par des séances.");
        }
        return "redirect:/admin/referentiels";
    }

    // --- Campus ---
    @PostMapping("/admin/campus")
    public String creerCampus(@RequestParam String nom,
                              @RequestParam(required = false) String adresse,
                              RedirectAttributes ra) {
        String n = nom == null ? "" : nom.trim();
        if (n.isEmpty()) {
            ra.addFlashAttribute("error", "Le nom de l'établissement est obligatoire.");
            return "redirect:/admin/referentiels";
        }
        if (campusRepository.existsByNomIgnoreCase(n)) {
            ra.addFlashAttribute("error", "Cet établissement existe déjà : " + n);
            return "redirect:/admin/referentiels";
        }
        try {
            // Le quota se compte AVANT l'ajout : le plan gratuit n'en autorise qu'un.
            planService.verifierQuotaCampus(etablissementCourantService.courant(),
                    campusRepository.count());
        } catch (ci.esatic.sigep.tenant.plan.PlanLimiteException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/referentiels";
        }
        campusRepository.save(ci.esatic.sigep.entity.Campus.builder()
                .nom(n)
                .adresse(adresse != null && !adresse.isBlank() ? adresse.trim() : null)
                .build());
        ra.addFlashAttribute("success", "Établissement « " + n + " » ajouté.");
        return "redirect:/admin/referentiels";
    }

    @PostMapping("/admin/campus/{id}/supprimer")
    public String supprimerCampus(@PathVariable Long id, RedirectAttributes ra) {
        long sallesRattachees = salleRepository.countByCampusId(id);
        if (sallesRattachees > 0) {
            // Refuser plutôt que détacher en silence : l'administrateur doit savoir que des
            // salles perdraient leur site, et décider lui-même où les remettre.
            ra.addFlashAttribute("error", "Suppression impossible : " + sallesRattachees
                    + " salle(s) sont rattachées à cet établissement. Déplacez-les d'abord.");
            return "redirect:/admin/referentiels";
        }
        try {
            campusRepository.deleteById(id);
            ra.addFlashAttribute("success", "Établissement supprimé.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Suppression impossible.");
        }
        return "redirect:/admin/referentiels";
    }

    // --- Salles ---
    @PostMapping("/admin/salles")
    public String creerSalle(@RequestParam String libelle, @RequestParam(required = false) String batiment,
                             @RequestParam(required = false) Integer capacite,
                             @RequestParam(required = false) Long campusId, RedirectAttributes ra) {
        String lib = libelle == null ? "" : libelle.trim().toUpperCase();
        if (lib.isEmpty()) {
            ra.addFlashAttribute("error", "Le nom de la salle est obligatoire.");
        } else if (!lib.matches("[A-Z0-9_\\-]{1,20}")) {
            ra.addFlashAttribute("error",
                    "Le nom de salle doit être court et sans espace (lettres, chiffres, - ou _). Ex : A101.");
        } else if (salleRepository.existsByLibelleIgnoreCase(lib)) {
            ra.addFlashAttribute("error", "Cette salle existe déjà : " + lib);
        } else {
            // Le campus n'est retenu que s'il appartient bien à cet établissement : le filtre
            // multi-tenant rend introuvable celui d'un autre, et l'identifiant est alors ignoré.
            Long campus = (campusId != null && campusRepository.existsById(campusId)) ? campusId : null;
            salleRepository.save(Salle.builder().libelle(lib)
                    .batiment(batiment != null ? batiment.trim() : null).capacite(capacite)
                    .campusId(campus).build());
            ra.addFlashAttribute("success", "Salle « " + lib + " » ajoutée.");
        }
        return "redirect:/admin/referentiels";
    }

    @PostMapping("/admin/salles/{id}/supprimer")
    public String supprimerSalle(@PathVariable Long id, RedirectAttributes ra) {
        try {
            salleRepository.deleteById(id);
            ra.addFlashAttribute("success", "Salle supprimée.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Suppression impossible : cette salle est utilisée par des séances.");
        }
        return "redirect:/admin/referentiels";
    }

    // --- Dépôt de l'emploi du temps ---
    @PostMapping("/admin/planning/importer")
    public String importerEmploiDuTemps(
            @RequestParam("fichier") org.springframework.web.multipart.MultipartFile fichier,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate fin,
            @RequestParam(required = false) String classe,
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
        String nom = fichier.getOriginalFilename() == null ? "" : fichier.getOriginalFilename().toLowerCase();
        if (fichier.isEmpty()) {
            ra.addFlashAttribute("error", "Déposez votre emploi du temps.");
            return "redirect:/admin/rapports";
        }
        if (ci.esatic.sigep.service.PlanningIaService.photoIphone(nom)) {
            ra.addFlashAttribute("error", "Ce format de photo n'est pas lisible : enregistrez-la en JPEG ou PNG.");
            return "redirect:/admin/rapports";
        }
        boolean tableur = nom.endsWith(".xlsx") || nom.endsWith(".xls") || nom.endsWith(".csv");
        if (!tableur) {
            return lireParIa(fichier, nom, debut, fin, classe, model, ra);
        }
        try {
            java.util.Map<String, Object> resultat = importService.importerPlanning(fichier, debut, fin, classe);
            int importees = (Integer) resultat.get("totalImporte");
            int doublons = (Integer) resultat.get("doublonsIgnores");
            int enErreur = (Integer) resultat.get("lignesEnErreur");
            int fermes = (Integer) resultat.get("joursFermesIgnores");
            // Rien de compris par les règles : la mise en page sort de l'ordinaire.
            if (importees == 0 && doublons == 0 && fermes == 0 && planningIaService.disponible()) {
                return lireParIa(fichier, nom, debut, fin, classe, model, ra);
            }
            String message = importees + " séance(s) importée(s)"
                    + (doublons > 0 ? ", " + doublons + " déjà présente(s) ignorée(s)" : "")
                    + (fermes > 0 ? ", " + fermes + " date(s) sautée(s) : établissement fermé" : "")
                    + (enErreur > 0 ? ", " + enErreur + " ligne(s) à corriger" : "") + ".";
            ra.addFlashAttribute(importees > 0 || enErreur == 0 ? "success" : "error", message);
            ra.addFlashAttribute("erreursImport", resultat.get("erreurs"));
        } catch (Exception e) {
            if (planningIaService.disponible()) {
                return lireParIa(fichier, nom, debut, fin, classe, model, ra);
            }
            ra.addFlashAttribute("error", e instanceof IllegalArgumentException
                    ? "Import impossible : " + e.getMessage()
                    : "Ce fichier n'a pas pu être lu. Vérifiez qu'il s'ouvre bien dans Excel.");
        }
        return "redirect:/admin/rapports";
    }

    /** L'IA lit le document ; rien n'est enregistré avant la relecture par l'établissement. */
    private String lireParIa(org.springframework.web.multipart.MultipartFile fichier, String nom, LocalDate debut, LocalDate fin,
                             String classe, Model model, RedirectAttributes ra) {
        try {
            byte[] octets = fichier.getBytes();
            var type = ci.esatic.sigep.service.PlanningIaService.TypeDocument.image(octets);
            if (type == null) {
                if (!(nom.endsWith(".xlsx") || nom.endsWith(".xls") || nom.endsWith(".csv") || nom.endsWith(".docx"))) {
                    ra.addFlashAttribute("error", "Format non pris en charge. Déposez un fichier Excel, CSV, Word (.docx), PDF, ou une photo JPEG ou PNG.");
                    return "redirect:/admin/rapports";
                }
                octets = importService.transcrire(fichier).getBytes(StandardCharsets.UTF_8);
                type = ci.esatic.sigep.service.PlanningIaService.TypeDocument.TEXTE;
            }
            var etab = etablissementCourantService.courant();
            var lu = planningIaService.lire(octets, type, importService.contexteEtablissement(), etab == null ? null : etab.getId());
            if (lu.lignes().isEmpty()) {
                ra.addFlashAttribute("error", lu.remarque() == null || lu.remarque().isBlank()
                        ? "Aucune séance trouvée dans ce document." : "Aucune séance trouvée : " + lu.remarque());
                return "redirect:/admin/rapports";
            }
            model.addAttribute("lignes", importService.preparerRelecture(lu, classe));
            model.addAttribute("remarque", lu.remarque());
            model.addAttribute("debut", debut != null ? debut : dateLue(lu.periodeDebut()));
            model.addAttribute("fin", fin != null ? fin : dateLue(lu.periodeFin()));
            model.addAttribute("enseignants", enseignantRepository.findByStatutOrderByNomAsc(ci.esatic.sigep.entity.StatutEnseignant.VALIDATED));
            return "admin/planning-relecture";
        } catch (ci.esatic.sigep.service.PlanningIaService.LectureImpossible e) {
            ra.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Ce fichier n'a pas pu être lu.");
        }
        return "redirect:/admin/rapports";
    }

    private static LocalDate dateLue(String valeur) {
        try {
            return valeur == null ? null : LocalDate.parse(valeur);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static final int LIGNES_RELUES_MAX = 5000;

    @PostMapping("/admin/planning/confirmer")
    public String confirmerImport(
            @RequestParam String lignes,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate fin,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate semaineA,
            RedirectAttributes ra) {
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
        var resultat = importService.importerRelues(relues, debut, fin, semaineA);
        int importees = (Integer) resultat.get("totalImporte");
        int doublons = (Integer) resultat.get("doublonsIgnores");
        int fermes = (Integer) resultat.get("joursFermesIgnores");
        int enErreur = (Integer) resultat.get("lignesEnErreur");
        ra.addFlashAttribute(importees > 0 || enErreur == 0 ? "success" : "error", importees + " séance(s) importée(s)"
                + (doublons > 0 ? ", " + doublons + " déjà présente(s) ignorée(s)" : "")
                + (fermes > 0 ? ", " + fermes + " date(s) sautée(s) : établissement fermé" : "")
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
