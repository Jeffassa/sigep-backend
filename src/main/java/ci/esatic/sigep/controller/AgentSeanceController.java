package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.request.SeanceRequest;
import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.SeanceResponse;
import ci.esatic.sigep.entity.DemandeRattrapage;
import ci.esatic.sigep.entity.Emargement;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Salle;
import ci.esatic.sigep.entity.Seance;
import ci.esatic.sigep.entity.StatutDemande;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.TypeSeance;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.ClasseRepository;
import ci.esatic.sigep.repository.DemandeRattrapageRepository;
import ci.esatic.sigep.repository.EmargementRepository;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.MatiereRepository;
import ci.esatic.sigep.repository.SalleRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import ci.esatic.sigep.security.AgentAuthFilter;
import ci.esatic.sigep.service.EmargementService;
import ci.esatic.sigep.service.RattrapageService;
import ci.esatic.sigep.service.SeanceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API de l'agent : la page Alertes de la console (séances importées à valider, rattrapages,
 * émargements hors ligne) et la planification d'une séance.
 *
 * <p>Mêmes services que la console. Chaque écriture accepte {@code ?simulation=true}. La validation
 * par lot porte sur une liste d'identifiants relue au préalable (la simulation la fournit) : une
 * séance arrivée entre-temps n'est jamais acceptée sans avoir été vue.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentSeanceController {

    private static final int MAX_LISTE = 50;

    private final SeanceService seanceService;
    private final RattrapageService rattrapageService;
    private final EmargementService emargementService;
    private final SeanceRepository seanceRepository;
    private final DemandeRattrapageRepository rattrapageRepository;
    private final EmargementRepository emargementRepository;
    private final EnseignantRepository enseignantRepository;
    private final SalleRepository salleRepository;
    private final MatiereRepository matiereRepository;
    private final ClasseRepository classeRepository;

    // ── Lectures ──

    /** Ce que la page Alertes montre, en une fois. */
    @GetMapping("/alertes")
    public ApiResponse<Map<String, Object>> alertes(HttpServletRequest requete) {
        List<Seance> nonEmargees = seanceRepository.findSeancesNonEmargees(LocalDate.now(), LocalTime.now());
        List<Seance> aValider = seanceService.getSeancesAValider();
        List<DemandeRattrapage> rattrapages =
                rattrapageRepository.findByStatutOrderByDateCreationDesc(StatutDemande.EN_ATTENTE);
        List<Emargement> horsLigne = emargementRepository.findEmargementsHorsLigneEnAttente();
        List<Enseignant> comptes = enseignantRepository.findByStatutOrderByNomAsc(StatutEnseignant.PENDING);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seancesAValider", aValider.stream().limit(MAX_LISTE).map(AgentSeanceController::vue).toList());
        data.put("nbSeancesAValider", aValider.size());
        data.put("rattrapagesEnAttente", rattrapages.stream().limit(MAX_LISTE).map(AgentSeanceController::vue).toList());
        data.put("nbRattrapagesEnAttente", rattrapages.size());
        data.put("emargementsHorsLigne", horsLigne.stream().limit(MAX_LISTE).map(AgentSeanceController::vue).toList());
        data.put("nbEmargementsHorsLigne", horsLigne.size());
        data.put("seancesNonEmargees", nonEmargees.stream().limit(MAX_LISTE).map(AgentSeanceController::vue).toList());
        data.put("nbSeancesNonEmargees", nonEmargees.size());
        data.put("nbEnseignantsEnAttente", comptes.size());
        resumer(requete, "Consultation des alertes");
        return ApiResponse.success(aValider.size() + " séance(s) à valider, " + rattrapages.size()
                + " rattrapage(s), " + horsLigne.size() + " émargement(s) hors ligne, "
                + nonEmargees.size() + " séance(s) passée(s) non émargée(s).", data);
    }

    /** Les séances d'une journée. */
    @GetMapping("/seances")
    public ApiResponse<Map<String, Object>> seancesDuJour(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest requete) {
        LocalDate jour = date != null ? date : LocalDate.now();
        List<SeanceResponse> seances = seanceService.getSeancesAdminJour(jour);
        resumer(requete, "Consultation des séances du " + jour);
        return ApiResponse.success(seances.size() + " séance(s) le " + jour + ".",
                Map.of("date", jour.toString(), "seances", seances.stream().map(AgentSeanceController::vue).toList()));
    }

    /** Salles, matières et classes : les identifiants dont la planification a besoin. */
    @GetMapping("/referentiels")
    public ApiResponse<Map<String, Object>> referentiels(HttpServletRequest requete) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("salles", salleRepository.findAll().stream().map(s -> ligne(s.getId(),
                s.getLibelle() + (s.getBatiment() != null ? " (" + s.getBatiment() + ")" : ""))).toList());
        data.put("matieres", matiereRepository.findAll().stream().map(m -> ligne(m.getId(), m.getLibelle())).toList());
        data.put("classes", classeRepository.findAll().stream().map(c -> ligne(c.getId(), c.getLibelle())).toList());
        resumer(requete, "Consultation des référentiels");
        return ApiResponse.success("Référentiels de l'établissement.", data);
    }

    // ── Séances importées par les enseignants ──

    @PostMapping("/seances/{id}/valider")
    public ApiResponse<Map<String, Object>> validerSeance(@PathVariable Long id,
                                                          @RequestParam(defaultValue = "false") boolean simulation,
                                                          HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        String quoi = decrire(seanceService.seanceAValider(id));
        if (simulation) {
            return fait(requete, "Simulation : la séance " + quoi + " serait acceptée et deviendrait émargeable.", true);
        }
        seanceService.validerImport(id);
        return fait(requete, "Séance " + quoi + " acceptée : l'enseignant peut l'émarger.", false);
    }

    /** Corps d'une validation par lot : vide en simulation = toutes les séances en attente. */
    public record Lot(List<Long> ids) { }

    @PostMapping("/seances/valider")
    public ApiResponse<Map<String, Object>> validerSeances(@RequestBody(required = false) Lot lot,
                                                           @RequestParam(defaultValue = "false") boolean simulation,
                                                           HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        List<Long> ids = lot == null || lot.ids() == null ? List.of() : lot.ids();
        if (simulation) {
            List<Seance> seances = ids.isEmpty() ? seanceService.getSeancesAValider()
                                                 : seanceService.verifierImportsEnAttente(ids);
            String message = "Simulation : " + seances.size() + " séance(s) seraient acceptées.";
            resumer(requete, message);
            return ApiResponse.success(message, Map.of("simulation", true,
                    "ids", seances.stream().map(Seance::getId).toList(),
                    "seances", seances.stream().map(AgentSeanceController::vue).toList()));
        }
        if (ids.isEmpty()) {
            throw new MetierException("CHAMP_MANQUANT",
                    "Indiquez les séances à accepter : une simulation en donne la liste.");
        }
        int n = seanceService.validerImports(ids);
        return fait(requete, n + " séance(s) acceptée(s) : les enseignants peuvent les émarger.", false);
    }

    @PostMapping("/seances/{id}/refuser")
    public ApiResponse<Map<String, Object>> refuserSeance(@PathVariable Long id,
                                                          @RequestParam(defaultValue = "false") boolean simulation,
                                                          HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        String quoi = decrire(seanceService.seanceAValider(id));
        if (simulation) {
            return fait(requete, "Simulation : la séance " + quoi + " serait écartée.", true);
        }
        seanceService.refuserImport(id);
        return fait(requete, "Séance " + quoi + " écartée.", false);
    }

    // ── Planification ──

    /** Corps attendu pour planifier une séance. */
    public record Planification(Long enseignantId, Long matiereId, Long classeId, Long salleId,
                                LocalDate date, LocalTime heureDebut, LocalTime heureFin, TypeSeance type) { }

    @PostMapping("/seances")
    public ResponseEntity<ApiResponse<Map<String, Object>>> planifier(@RequestBody Planification p,
                                                                      @RequestParam(defaultValue = "false") boolean simulation,
                                                                      HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        SeanceRequest demande = new SeanceRequest();
        demande.setEnseignantId(p.enseignantId());
        demande.setMatiereId(p.matiereId());
        demande.setClasseId(p.classeId());
        demande.setSalleId(p.salleId());
        demande.setDate(p.date());
        demande.setHeureDebut(p.heureDebut());
        demande.setHeureFin(p.heureFin());
        demande.setType(p.type() != null ? p.type() : TypeSeance.NORMALE);
        seanceService.verifierCreation(demande);
        if (simulation) {
            return ResponseEntity.ok(fait(requete, "Simulation : une séance serait planifiée le " + p.date()
                    + " de " + p.heureDebut() + " à " + p.heureFin() + ".", true));
        }
        SeanceResponse s = seanceService.create(demande);
        String message = "Séance planifiée : " + decrire(s) + ".";
        resumer(requete, message);
        Map<String, Object> data = vue(s);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message, data));
    }

    // ── Rattrapages ──

    /** Corps d'une acceptation : la salle où aura lieu le rattrapage. */
    public record Acceptation(Long salleId) { }

    @PostMapping("/rattrapages/{id}/accepter")
    public ApiResponse<Map<String, Object>> accepterRattrapage(@PathVariable Long id,
                                                               @RequestBody Acceptation corps,
                                                               @RequestParam(defaultValue = "false") boolean simulation,
                                                               HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        if (corps == null || corps.salleId() == null) {
            throw new MetierException("CHAMP_MANQUANT", "Indiquez la salle du rattrapage.");
        }
        DemandeRattrapage d = rattrapageService.demandeEnAttente(id);
        Salle salle = salleRepository.findById(corps.salleId())
                .orElseThrow(() -> new ResourceNotFoundException("Salle", "id", corps.salleId()));
        String quoi = decrire(d) + " en salle " + salle.getLibelle();
        if (simulation) {
            return fait(requete, "Simulation : le rattrapage " + quoi
                    + " serait accepté, la séance créée et l'enseignant prévenu par e-mail.", true);
        }
        rattrapageService.accepterAvecSalle(id, salle);
        return fait(requete, "Rattrapage " + quoi + " accepté : la séance est créée et l'enseignant prévenu.", false);
    }

    @PostMapping("/rattrapages/{id}/refuser")
    public ApiResponse<Map<String, Object>> refuserRattrapage(@PathVariable Long id,
                                                              @RequestParam(defaultValue = "false") boolean simulation,
                                                              HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        String quoi = decrire(rattrapageService.demandeEnAttente(id));
        if (simulation) {
            return fait(requete, "Simulation : le rattrapage " + quoi
                    + " serait refusé et l'enseignant prévenu par e-mail.", true);
        }
        rattrapageService.refuser(id);
        return fait(requete, "Rattrapage " + quoi + " refusé : l'enseignant est prévenu.", false);
    }

    // ── Émargements hors ligne ──

    @PostMapping("/emargements-hors-ligne/{id}/valider")
    public ApiResponse<Map<String, Object>> validerHorsLigne(@PathVariable Long id,
                                                             @RequestParam(defaultValue = "false") boolean simulation,
                                                             HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        String quoi = decrire(emargementService.horsLigneEnAttente(id));
        if (simulation) {
            return fait(requete, "Simulation : l'émargement hors ligne " + quoi
                    + " serait validé ; la séance serait comptée.", true);
        }
        emargementService.validerHorsLigne(id);
        return fait(requete, "Émargement hors ligne " + quoi + " validé : la présence est confirmée.", false);
    }

    @PostMapping("/emargements-hors-ligne/{id}/refuser")
    public ApiResponse<Map<String, Object>> refuserHorsLigne(@PathVariable Long id,
                                                             @RequestParam(defaultValue = "false") boolean simulation,
                                                             HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        String quoi = decrire(emargementService.horsLigneEnAttente(id));
        if (simulation) {
            return fait(requete, "Simulation : l'émargement hors ligne " + quoi
                    + " serait refusé ; la séance redeviendrait à régulariser.", true);
        }
        emargementService.refuserHorsLigne(id);
        return fait(requete, "Émargement hors ligne " + quoi + " refusé : la séance reste à régulariser.", false);
    }

    // ── Mise en forme ──

    private static ApiResponse<Map<String, Object>> fait(HttpServletRequest requete, String message, boolean simulation) {
        resumer(requete, message);
        return ApiResponse.success(message, simulation ? Map.of("simulation", true) : Map.of());
    }

    private static Map<String, Object> ligne(Long id, String libelle) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("libelle", libelle);
        return m;
    }

    private static String qui(Enseignant e) {
        return e == null ? "?" : e.getPrenom() + " " + e.getNom();
    }

    private static String decrire(Seance s) {
        return "n° " + s.getId() + " (" + (s.getMatiere() != null ? s.getMatiere().getLibelle() : "?")
                + ", " + qui(s.getEnseignant()) + ", le " + s.getDate() + " à " + s.getHeureDebut() + ")";
    }

    private static String decrire(SeanceResponse s) {
        return "n° " + s.getId() + " (" + s.getMatiereLibelle() + ", " + s.getEnseignantPrenom() + " "
                + s.getEnseignantNom() + ", le " + s.getDate() + " de " + s.getHeureDebut() + " à " + s.getHeureFin() + ")";
    }

    private static String decrire(DemandeRattrapage d) {
        return "n° " + d.getId() + " (" + (d.getMatiere() != null ? d.getMatiere().getLibelle() : "?") + ", "
                + qui(d.getEnseignant()) + ", le " + d.getDateSouhaitee() + " à " + d.getHeureSouhaitee() + ")";
    }

    private static String decrire(Emargement e) {
        return "n° " + e.getId() + " (" + qui(e.getEnseignant()) + ", séance du "
                + (e.getSeance() != null ? e.getSeance().getDate() : "?") + ")";
    }

    private static Map<String, Object> vue(Seance s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("date", String.valueOf(s.getDate()));
        m.put("heureDebut", String.valueOf(s.getHeureDebut()));
        m.put("heureFin", String.valueOf(s.getHeureFin()));
        m.put("matiere", s.getMatiere() != null ? s.getMatiere().getLibelle() : null);
        m.put("classe", s.getClasse() != null ? s.getClasse().getLibelle() : null);
        m.put("salle", s.getSalle() != null ? s.getSalle().getLibelle() : null);
        m.put("enseignant", qui(s.getEnseignant()));
        m.put("type", s.getType() != null ? s.getType().name() : null);
        m.put("statut", s.getStatut() != null ? s.getStatut().name() : null);
        return m;
    }

    private static Map<String, Object> vue(SeanceResponse s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("date", String.valueOf(s.getDate()));
        m.put("heureDebut", String.valueOf(s.getHeureDebut()));
        m.put("heureFin", String.valueOf(s.getHeureFin()));
        m.put("matiere", s.getMatiereLibelle());
        m.put("classe", s.getClasseLibelle());
        m.put("salle", s.getSalleLibelle());
        m.put("enseignant", s.getEnseignantPrenom() + " " + s.getEnseignantNom());
        m.put("type", s.getType() != null ? s.getType().name() : null);
        m.put("statut", s.getStatut() != null ? s.getStatut().name() : null);
        return m;
    }

    private static Map<String, Object> vue(DemandeRattrapage d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("enseignant", qui(d.getEnseignant()));
        m.put("matiere", d.getMatiere() != null ? d.getMatiere().getLibelle() : null);
        m.put("classe", d.getClasse() != null ? d.getClasse().getLibelle() : null);
        m.put("date", String.valueOf(d.getDateSouhaitee()));
        m.put("heure", String.valueOf(d.getHeureSouhaitee()));
        m.put("motif", d.getMotif());
        return m;
    }

    private static Map<String, Object> vue(Emargement e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("enseignant", qui(e.getEnseignant()));
        m.put("dateHeure", String.valueOf(e.getDateHeure()));
        m.put("seance", e.getSeance() != null ? vue(e.getSeance()) : null);
        return m;
    }

    private static void marquerSimulation(HttpServletRequest requete, boolean simulation) {
        requete.setAttribute(AgentAuthFilter.ATTR_SIMULATION, simulation);
    }

    private static void resumer(HttpServletRequest requete, String resume) {
        requete.setAttribute(AgentAuthFilter.ATTR_RESUME, resume);
    }
}
