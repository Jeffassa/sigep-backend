package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.Classe;
import ci.esatic.sigep.entity.Matiere;
import ci.esatic.sigep.entity.Salle;
import ci.esatic.sigep.repository.ClasseRepository;
import ci.esatic.sigep.repository.MatiereRepository;
import ci.esatic.sigep.repository.SalleRepository;
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

    @GetMapping("/admin/referentiels")
    public String referentiels(Model model) {
        model.addAttribute("matieres", matiereRepository.findAll());
        model.addAttribute("classes", classeRepository.findAll());
        model.addAttribute("salles", salleRepository.findAll());
        return "admin/referentiels";
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

    // --- Salles ---
    @PostMapping("/admin/salles")
    public String creerSalle(@RequestParam String libelle, @RequestParam(required = false) String batiment,
                             @RequestParam(required = false) Integer capacite, RedirectAttributes ra) {
        String lib = libelle == null ? "" : libelle.trim().toUpperCase();
        if (lib.isEmpty()) {
            ra.addFlashAttribute("error", "Le nom de la salle est obligatoire.");
        } else if (!lib.matches("[A-Z0-9_\\-]{1,20}")) {
            ra.addFlashAttribute("error",
                    "Le nom de salle doit être court et sans espace (lettres, chiffres, - ou _). Ex : A101.");
        } else if (salleRepository.existsByLibelleIgnoreCase(lib)) {
            ra.addFlashAttribute("error", "Cette salle existe déjà : " + lib);
        } else {
            salleRepository.save(Salle.builder().libelle(lib)
                    .batiment(batiment != null ? batiment.trim() : null).capacite(capacite).build());
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

    // --- Modèle d'emploi du temps (CSV téléchargeable) ---
    @GetMapping("/admin/planning/modele")
    public ResponseEntity<byte[]> modeleEmploiDuTemps() {
        String m  = matiereRepository.findAll().stream().findFirst().map(Matiere::getLibelle).orElse("MATIERE");
        String cl = classeRepository.findAll().stream().findFirst().map(Classe::getLibelle).orElse("CLASSE");
        String sa = salleRepository.findAll().stream().findFirst().map(Salle::getLibelle).orElse("SALLE");
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        StringBuilder sb = new StringBuilder();
        sb.append("DATE;HEURE_DEBUT;HEURE_FIN;MATIERE;CLASSE;SALLE\r\n");
        sb.append(today).append(";08:00;10:00;").append(m).append(';').append(cl).append(';').append(sa).append("\r\n");
        sb.append(today).append(";10:15;12:15;").append(m).append(';').append(cl).append(';').append(sa).append("\r\n");

        // BOM UTF-8 pour qu'Excel ouvre correctement les accents
        byte[] bytes = ("﻿" + sb).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"modele_emploi_du_temps.csv\"")
                .body(bytes);
    }
}
