package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.PeriodeCalendrier;
import ci.esatic.sigep.entity.TypePeriode;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.repository.CampusRepository;
import ci.esatic.sigep.repository.PeriodeCalendrierRepository;
import ci.esatic.sigep.security.AgentAuthFilter;
import ci.esatic.sigep.service.CalendrierService;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.service.ImportService;
import ci.esatic.sigep.service.ReferentielService;
import ci.esatic.sigep.service.SimulationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * API de l'agent : référentiels (matières, classes, salles, sites), calendrier (périodes,
 * tolérance, remplacements) et import d'un emploi du temps.
 *
 * <p>Mêmes services que la console. En simulation ({@code ?simulation=true}), l'action est jouée
 * puis annulée ({@link SimulationService}) ; le remplacement, qui écrit au remplaçant, a son propre
 * calcul sans effet.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentReferentielController {

    private final ReferentielService referentielService;
    private final CalendrierService calendrierService;
    private final ImportService importService;
    private final SimulationService simulationService;
    private final CampusRepository campusRepository;
    private final PeriodeCalendrierRepository periodeRepository;
    private final EtablissementCourantService etablissementCourantService;

    // ── Référentiels ──

    /** Corps d'un ajout : seuls les champs du type concerné sont lus. */
    public record Ajout(String libelle, String description, String filiere, Integer niveau,
                        String batiment, Integer capacite, Long campusId, String nom, String adresse) { }

    @GetMapping("/campus")
    public ApiResponse<Map<String, Object>> campus(HttpServletRequest requete) {
        resumer(requete, "Consultation des sites");
        return ApiResponse.success("Sites de l'établissement.", Map.of("campus",
                campusRepository.findAllByOrderByNomAsc().stream().map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("nom", c.getNom());
                    m.put("adresse", c.getAdresse());
                    return m;
                }).toList()));
    }

    @PostMapping("/referentiels/{type}")
    public ApiResponse<Map<String, Object>> ajouter(@PathVariable String type, @RequestBody Ajout a,
                                                    @RequestParam(defaultValue = "false") boolean simulation,
                                                    HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        ReferentielService.Type t = type(type);
        java.util.function.Supplier<Object[]> action = () -> switch (t) {
            case MATIERE -> { var m = referentielService.creerMatiere(a.libelle(), a.description());
                yield new Object[]{m.getId(), "la matière « " + m.getLibelle() + " »"}; }
            case CLASSE -> { var c = referentielService.creerClasse(a.libelle(), a.filiere(), a.niveau());
                yield new Object[]{c.getId(), "la classe « " + c.getLibelle() + " »"}; }
            case SALLE -> { var s = referentielService.creerSalle(a.libelle(), a.batiment(), a.capacite(), a.campusId());
                yield new Object[]{s.getId(), "la salle « " + s.getLibelle() + " »"}; }
            case CAMPUS -> { var c = referentielService.creerCampus(a.nom(), a.adresse());
                yield new Object[]{c.getId(), "le site « " + c.getNom() + " »"}; }
        };
        if (simulation) {
            Object[] r = simulationService.simuler(action);
            return fait(requete, "Simulation : " + r[1] + " serait ajouté(e).", Map.of("simulation", true));
        }
        Object[] r = action.get();
        return fait(requete, "Ajouté : " + r[1] + ".", Map.of("id", r[0]));
    }

    @PostMapping("/referentiels/{type}/{id}/supprimer")
    public ApiResponse<Map<String, Object>> supprimer(@PathVariable String type, @PathVariable Long id,
                                                      @RequestParam(defaultValue = "false") boolean simulation,
                                                      HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        ReferentielService.Type t = type(type);
        if (simulation) {
            String libelle = referentielService.verifierSuppression(t, id);
            return fait(requete, "Simulation : « " + libelle + " » serait supprimé(e).", Map.of("simulation", true));
        }
        String libelle = referentielService.supprimer(t, id);
        return fait(requete, "Supprimé : « " + libelle + " ».", Map.of());
    }

    /** Corps d'un changement de format : STANDARD, UNIVERSITAIRE ou SECONDAIRE. */
    public record Format(String format) { }

    @PostMapping("/planning/format")
    public ApiResponse<Map<String, Object>> format(@RequestBody Format f,
                                                   @RequestParam(defaultValue = "false") boolean simulation,
                                                   HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        String valeur = f == null ? null : f.format();
        if (simulation) {
            String v = simulationService.simuler(() -> referentielService.changerFormatPlanning(valeur));
            return fait(requete, "Simulation : le format d'emploi du temps deviendrait " + v + ".", Map.of("simulation", true));
        }
        return fait(requete, "Format d'emploi du temps : " + referentielService.changerFormatPlanning(valeur) + ".", Map.of());
    }

    // ── Calendrier ──

    @GetMapping("/calendrier")
    public ApiResponse<Map<String, Object>> calendrier(HttpServletRequest requete) {
        Etablissement etab = etablissementCourantService.courant();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("periodes", periodeRepository.findAllByOrderByDebutAsc().stream().map(AgentReferentielController::vue).toList());
        data.put("toleranceAvantMinutes", etab != null ? etab.getToleranceAvantMinutes() : null);
        data.put("toleranceApresMinutes", etab != null ? etab.getToleranceApresMinutes() : null);
        data.put("formatEmploiDuTemps", etab != null ? etab.getFormatEmploiDuTemps() : null);
        resumer(requete, "Consultation du calendrier");
        return ApiResponse.success("Calendrier de l'établissement.", data);
    }

    /** Corps d'une période : FERIE, VACANCES ou SEMESTRE. */
    public record Periode(TypePeriode type, String libelle, LocalDate debut, LocalDate fin, boolean retirerSeances) { }

    @PostMapping("/calendrier/periodes")
    public ApiResponse<Map<String, Object>> ajouterPeriode(@RequestBody Periode p,
                                                           @RequestParam(defaultValue = "false") boolean simulation,
                                                           HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        if (p == null) throw new MetierException("CHAMP_MANQUANT", "Indiquez le type, le nom et la date de début.");
        java.util.function.Supplier<Integer> action =
                () -> calendrierService.ajouterPeriode(p.type(), p.libelle(), p.debut(), p.fin(), p.retirerSeances());
        String quoi = "« " + (p.libelle() == null ? "" : p.libelle().trim()) + " » (" + p.type() + ", du " + p.debut()
                + (p.fin() != null ? " au " + p.fin() : "") + ")";
        if (simulation) {
            int retirees = simulationService.simuler(action);
            return fait(requete, "Simulation : " + quoi + " serait ajouté au calendrier"
                    + (retirees > 0 ? ", et " + retirees + " séance(s) non émargée(s) seraient retirées." : "."),
                    Map.of("simulation", true, "seancesRetirees", retirees));
        }
        int retirees = action.get();
        return fait(requete, quoi + " ajouté au calendrier"
                + (retirees > 0 ? " : " + retirees + " séance(s) non émargée(s) retirée(s)." : "."),
                Map.of("seancesRetirees", retirees));
    }

    @PostMapping("/calendrier/periodes/{id}/supprimer")
    public ApiResponse<Map<String, Object>> supprimerPeriode(@PathVariable Long id,
                                                             @RequestParam(defaultValue = "false") boolean simulation,
                                                             HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        PeriodeCalendrier p = calendrierService.periode(id);
        if (simulation) {
            return fait(requete, "Simulation : « " + p.getLibelle() + " » serait retiré du calendrier ; "
                    + "les séances déjà retirées ne reviendraient pas.", Map.of("simulation", true));
        }
        calendrierService.supprimerPeriode(id);
        return fait(requete, "« " + p.getLibelle() + " » retiré du calendrier. Les séances déjà retirées ne reviennent pas.", Map.of());
    }

    /** Corps d'un réglage de tolérance, en minutes. */
    public record Tolerance(int avant, int apres) { }

    @PostMapping("/calendrier/tolerance")
    public ApiResponse<Map<String, Object>> tolerance(@RequestBody Tolerance t,
                                                      @RequestParam(defaultValue = "false") boolean simulation,
                                                      HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        String quoi = t.avant() + " min avant, " + t.apres() + " min après";
        if (simulation) {
            simulationService.simuler(() -> { calendrierService.reglerTolerance(t.avant(), t.apres()); return null; });
            return fait(requete, "Simulation : la tolérance d'émargement passerait à " + quoi + ".", Map.of("simulation", true));
        }
        calendrierService.reglerTolerance(t.avant(), t.apres());
        return fait(requete, "Tolérance d'émargement : " + quoi + ".", Map.of());
    }

    /** Corps d'un remplacement. */
    public record Remplacement(Long absentId, Long remplacantId, LocalDate debut, LocalDate fin) { }

    @PostMapping("/calendrier/remplacement")
    public ApiResponse<Map<String, Object>> remplacer(@RequestBody Remplacement r,
                                                      @RequestParam(defaultValue = "false") boolean simulation,
                                                      HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        if (simulation) {
            var s = calendrierService.simulerRemplacement(r.absentId(), r.remplacantId(), r.debut(), r.fin());
            return fait(requete, "Simulation : " + s.remplacees() + " séance(s) seraient confiées au remplaçant, qui en serait prévenu"
                    + (s.conflits() > 0 ? " ; " + s.conflits() + " resteraient à l'enseignant prévu (créneaux déjà pris)." : "."),
                    Map.of("simulation", true, "remplacees", s.remplacees(), "conflits", s.conflits()));
        }
        var s = calendrierService.remplacer(r.absentId(), r.remplacantId(), r.debut(), r.fin());
        return fait(requete, s.remplacees() + " séance(s) confiée(s) au remplaçant"
                + (s.conflits() > 0 ? ", " + s.conflits() + " laissée(s) : il enseigne déjà sur ces créneaux." : "."),
                Map.of("remplacees", s.remplacees(), "conflits", s.conflits()));
    }

    // ── Import d'un emploi du temps ──

    @PostMapping(value = "/planning/importer", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> importer(
            @RequestParam("fichier") MultipartFile fichier,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin,
            @RequestParam(required = false) String classe,
            @RequestParam(required = false) Long enseignantId,
            @RequestParam(defaultValue = "false") boolean simulation,
            HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        String nom = fichier.getOriginalFilename() == null ? "" : fichier.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (fichier.isEmpty()) throw new MetierException("CHAMP_MANQUANT", "Le fichier est vide.");
        if (nom.endsWith(".pdf")) {
            throw new MetierException("FORMAT_NON_PRIS",
                    "Un PDF se relit ligne à ligne avant l'import : déposez-le dans la console (Rapports, Emploi du temps).");
        }
        if (!(nom.endsWith(".xlsx") || nom.endsWith(".xls") || nom.endsWith(".csv") || nom.endsWith(".docx"))) {
            throw new MetierException("FORMAT_NON_PRIS", "Format non pris en charge : Excel, CSV ou Word (.docx).");
        }
        // Comme dans la console : sans période, les cours répétés valent pour la fin du semestre en cours.
        if (debut == null && fin == null) {
            var semestre = calendrierService.semestreDe(LocalDate.now()).orElse(null);
            if (semestre != null) {
                debut = LocalDate.now();
                fin = semestre.getFin();
            }
        }
        LocalDate d = debut, f = fin;
        java.util.function.Supplier<Map<String, Object>> action = () -> {
            try {
                return importService.importerPlanning(fichier, d, f, classe, enseignantId);
            } catch (IllegalArgumentException | MetierException e) {
                throw e;
            } catch (Exception e) {
                throw new MetierException("FICHIER_ILLISIBLE", "Ce fichier n'a pas pu être lu. Vérifiez qu'il s'ouvre bien.");
            }
        };
        Map<String, Object> r = simulation ? simulationService.simuler(action) : action.get();
        int importees = (Integer) r.get("totalImporte");
        int doublons = (Integer) r.get("doublonsIgnores");
        int fermes = (Integer) r.get("joursFermesIgnores");
        @SuppressWarnings("unchecked")
        List<String> erreurs = (List<String>) r.get("erreurs");
        @SuppressWarnings("unchecked")
        List<String> nouveaux = (List<String>) r.get("nouveauxReferentiels");
        String bilan = importees + " séance(s)" + (simulation ? " seraient importées" : " importée(s)")
                + (doublons > 0 ? ", " + doublons + " déjà présente(s) ignorée(s)" : "")
                + (fermes > 0 ? ", " + fermes + " date(s) sautée(s) : établissement fermé" : "")
                + (!erreurs.isEmpty() ? ", " + erreurs.size() + " point(s) à corriger" : "") + "."
                // Les noms viennent du fichier : ils restent dans les données, pas dans la phrase.
                + (nouveaux.isEmpty() ? "" : " " + nouveaux.size() + (simulation ? " élément(s) seraient ajoutés"
                        : " élément(s) ajouté(s)") + " aux référentiels (salles, matières, classes absentes) :"
                        + " vérifiez qu'il ne s'agit pas de fautes de frappe.");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("importees", importees);
        data.put("doublons", doublons);
        data.put("joursFermes", fermes);
        data.put("erreurs", erreurs.stream().limit(50).toList());
        data.put("nouveauxReferentiels", nouveaux);
        if (simulation) data.put("simulation", true);
        return fait(requete, (simulation ? "Simulation : " : "") + bilan, data);
    }

    // ── Outils ──

    private static ReferentielService.Type type(String valeur) {
        return switch (valeur == null ? "" : valeur.toLowerCase(Locale.ROOT)) {
            case "matiere", "matieres" -> ReferentielService.Type.MATIERE;
            case "classe", "classes" -> ReferentielService.Type.CLASSE;
            case "salle", "salles" -> ReferentielService.Type.SALLE;
            case "campus", "site", "sites" -> ReferentielService.Type.CAMPUS;
            default -> throw new MetierException("TYPE_INVALIDE", "Type inconnu : matiere, classe, salle ou campus.");
        };
    }

    private static Map<String, Object> vue(PeriodeCalendrier p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("type", p.getType().name());
        m.put("libelle", p.getLibelle());
        m.put("debut", String.valueOf(p.getDebut()));
        m.put("fin", String.valueOf(p.getFin()));
        return m;
    }

    private static ApiResponse<Map<String, Object>> fait(HttpServletRequest requete, String message, Map<String, Object> data) {
        requete.setAttribute(AgentAuthFilter.ATTR_RESUME, message);
        return ApiResponse.success(message, data);
    }

    private static void marquerSimulation(HttpServletRequest requete, boolean simulation) {
        requete.setAttribute(AgentAuthFilter.ATTR_SIMULATION, simulation);
    }

    private static void resumer(HttpServletRequest requete, String resume) {
        requete.setAttribute(AgentAuthFilter.ATTR_RESUME, resume);
    }
}
