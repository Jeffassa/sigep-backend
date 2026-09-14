package ci.esatic.sigep.controller;

import ci.esatic.sigep.repository.EmargementRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API publique de l'établissement : lecture seule.
 *
 * <p>Lecture seule à dessein. Une API qui écrit demande une réflexion bien plus lourde —
 * validation, idempotence, conflits avec l'application mobile — et l'usage attendu est de
 * verser les heures dans un système tiers, pas de les y modifier.
 *
 * <p>Le périmètre est celui de la clé : l'intercepteur multi-tenant a posé l'établissement
 * qu'elle désigne, et aucune requête de ce contrôleur ne peut en sortir.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class ApiPubliqueController {

    private final SeanceRepository seanceRepository;
    private final EnseignantRepository enseignantRepository;
    private final EmargementRepository emargementRepository;

    /** Répond à un appel de vérification : confirme que la clé est acceptée. */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("success", true, "message", "Clé acceptée.");
    }

    @GetMapping("/enseignants")
    public List<Map<String, Object>> enseignants() {
        return enseignantRepository.findAll().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("matricule", e.getMatricule());
            m.put("nom", e.getNom());
            m.put("prenom", e.getPrenom());
            m.put("departement", e.getDepartement());
            m.put("grade", e.getGrade());
            m.put("statut", e.getStatut() != null ? e.getStatut().name() : null);
            return m;
        }).toList();
    }

    /**
     * Séances d'une période, bornée à 92 jours.
     *
     * <p>La borne existe pour que l'appel reste prévisible : sans elle, une période de dix ans
     * ramènerait tout l'historique en une réponse, au détriment de tous les autres appels.
     */
    @GetMapping("/seances")
    public ResponseEntity<?> seances(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin) {

        if (fin.isBefore(debut)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "La date de fin précède la date de début."));
        }
        if (debut.plusDays(92).isBefore(fin)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "Période trop large : 92 jours au maximum."));
        }

        return ResponseEntity.ok(seanceRepository.findByDateBetweenOrderByDateAscHeureDebutAsc(debut, fin).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", s.getDate() != null ? s.getDate().toString() : null);
            m.put("heureDebut", s.getHeureDebut() != null ? s.getHeureDebut().toString() : null);
            m.put("heureFin", s.getHeureFin() != null ? s.getHeureFin().toString() : null);
            m.put("matiere", s.getMatiere() != null ? s.getMatiere().getLibelle() : null);
            m.put("classe", s.getClasse() != null ? s.getClasse().getLibelle() : null);
            m.put("salle", s.getSalle() != null ? s.getSalle().getLibelle() : null);
            m.put("enseignantMatricule",
                    s.getEnseignant() != null ? s.getEnseignant().getMatricule() : null);
            m.put("statut", s.getStatut() != null ? s.getStatut().name() : null);
            return m;
        }).toList());
    }

    /** Nombre d'émargements enregistrés : sert aux tableaux de bord tiers. */
    @GetMapping("/emargements/total")
    public Map<String, Object> totalEmargements() {
        return Map.of("total", emargementRepository.count());
    }
}
