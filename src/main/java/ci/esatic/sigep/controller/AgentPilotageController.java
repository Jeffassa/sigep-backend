package ci.esatic.sigep.controller;

import ci.esatic.sigep.dto.response.ApiResponse;
import ci.esatic.sigep.dto.response.LignePaie;
import ci.esatic.sigep.dto.response.RapportResponse;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.RapportPdf;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.security.AgentAuthFilter;
import ci.esatic.sigep.service.AbonnementService;
import ci.esatic.sigep.service.EcranEmargementService;
import ci.esatic.sigep.service.EquipeService;
import ci.esatic.sigep.service.EtablissementCourantService;
import ci.esatic.sigep.service.ExportPaieService;
import ci.esatic.sigep.service.LogoService;
import ci.esatic.sigep.service.RapportService;
import ci.esatic.sigep.service.SimulationService;
import ci.esatic.sigep.service.StatistiquesService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * API de l'agent : paie, rapports, statistiques, écran d'émargement, équipe, logo, abonnement.
 *
 * <p>Mêmes services que la console. Restent à un humain, dans la console : la clé de l'écran,
 * les accès d'un membre de l'équipe, le mot de passe, les clés d'API et les paiements.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentPilotageController {

    private static final DateTimeFormatter MOIS = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    /** Ce que la page Statistiques calcule pour l'affichage seul (cases de la carte de chaleur). */
    private static final Set<String> STATS_AFFICHAGE = Set.of("heatmap", "creneaux");

    private final ExportPaieService exportPaieService;
    private final RapportService rapportService;
    private final StatistiquesService statistiquesService;
    private final EcranEmargementService ecranEmargementService;
    private final EquipeService equipeService;
    private final LogoService logoService;
    private final AbonnementService abonnementService;
    private final SimulationService simulationService;
    private final PlanService planService;
    private final EtablissementCourantService etablissementCourantService;
    private final EnseignantRepository enseignantRepository;

    // ── Paie ──

    @GetMapping("/paie")
    public ApiResponse<Map<String, Object>> paie(@RequestParam(required = false) String mois, HttpServletRequest requete) {
        planService.exiger(etablissementCourantService.courant(), Feature.EXPORT_PAIE);
        YearMonth periode = mois(mois);
        List<LignePaie> lignes = exportPaieService.calculer(periode);
        resumer(requete, "Consultation de la paie " + periode.format(MOIS));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mois", periode.format(MOIS));
        data.put("totaux", exportPaieService.totaux(lignes));
        data.put("lignes", lignes);
        return ApiResponse.success("Heures du mois " + periode.format(MOIS) + ".", data);
    }

    @GetMapping("/paie/export")
    public ResponseEntity<byte[]> exporterPaie(@RequestParam(required = false) String mois,
                                               @RequestParam(defaultValue = "xlsx") String format,
                                               HttpServletRequest requete) throws IOException {
        Etablissement etab = etablissementCourantService.courant();
        planService.exiger(etab, Feature.EXPORT_PAIE);
        YearMonth periode = mois(mois);
        String ext = format.toLowerCase(Locale.ROOT);
        if (!ext.equals("csv") && !ext.equals("xlsx")) {
            throw new MetierException("FORMAT_INVALIDE", "Format d'export : csv ou xlsx.");
        }
        List<LignePaie> lignes = exportPaieService.calculer(periode);
        byte[] contenu = ext.equals("csv") ? exportPaieService.versCsv(lignes, periode) : exportPaieService.versExcel(lignes, periode);
        String slug = etab == null || etab.getSlug() == null ? "" : etab.getSlug().replaceAll("[^A-Za-z0-9_-]", "");
        resumer(requete, "Export de la paie " + periode.format(MOIS) + " (" + ext + ")");
        return fichier(contenu, "paie_" + (slug.isEmpty() ? "" : slug + "_") + periode.format(MOIS) + "." + ext,
                ext.equals("csv") ? MediaType.parseMediaType("text/csv; charset=UTF-8") : XLSX);
    }

    // ── Rapports ──

    @GetMapping("/rapports")
    public ApiResponse<Map<String, Object>> rapports(
            @RequestParam(required = false) Long enseignantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin,
            HttpServletRequest requete) {
        List<RapportResponse> tous = rapportService.getRapportsFiltres(enseignantId, debut, fin);
        resumer(requete, "Consultation des rapports");
        List<Map<String, Object>> vus = tous.stream().limit(100).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("enseignant", (r.getEnseignantPrenom() == null ? "" : r.getEnseignantPrenom() + " ")
                    + (r.getEnseignantNom() == null ? "" : r.getEnseignantNom()));
            m.put("debut", r.getPeriodeDebut());
            m.put("fin", r.getPeriodeFin());
            m.put("type", r.getType());
            m.put("genereLe", r.getDateGeneration());
            m.put("tauxEmargement", r.getTauxEmargement());
            m.put("niveau", r.getNiveau());
            return m;
        }).toList();
        return ApiResponse.success(tous.size() + " rapport(s).", Map.of("total", tous.size(), "rapports", vus));
    }

    public record Generation(LocalDate debut, LocalDate fin) { }

    @PostMapping("/rapports/generer")
    public ApiResponse<Map<String, Object>> generer(@RequestBody Generation g,
                                                    @RequestParam(defaultValue = "false") boolean simulation,
                                                    HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        if (g == null || g.debut() == null || g.fin() == null) {
            throw new MetierException("CHAMP_MANQUANT", "Indiquez le début et la fin de la période.");
        }
        if (g.debut().isAfter(g.fin())) {
            throw new MetierException("PERIODE_INVALIDE", "La date de début doit être antérieure ou égale à la date de fin.");
        }
        if (simulation) {
            long n = enseignantRepository.count();
            return fait(requete, "Simulation : un rapport PDF serait généré pour chacun des " + n
                    + " enseignant(s), du " + g.debut() + " au " + g.fin() + ".", Map.of("simulation", true, "enseignants", n));
        }
        int n = rapportService.genererTousRapports(g.debut(), g.fin()).size();
        return fait(requete, n + " rapport(s) généré(s), du " + g.debut() + " au " + g.fin() + ".", Map.of("generes", n));
    }

    @GetMapping("/rapports/{id}/fichier")
    public ResponseEntity<byte[]> telechargerRapport(@PathVariable Long id, HttpServletRequest requete) throws IOException {
        RapportPdf rapport;
        try {
            rapport = rapportService.getRapportEntity(id);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
        byte[] octets = rapportService.getRapportBytes(id);
        resumer(requete, "Téléchargement du rapport " + id);
        return fichier(octets, rapportService.nomFichierTelechargement(rapport), MediaType.APPLICATION_PDF);
    }

    @GetMapping("/rapports/synthese")
    public ResponseEntity<byte[]> synthese(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin,
            HttpServletRequest requete) throws IOException {
        planService.exiger(etablissementCourantService.courant(), Feature.RAPPORTS_AVANCES);
        LocalDate d = debut != null ? debut : LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDate f = fin != null ? fin : d.plusDays(6);
        if (d.isAfter(f)) throw new MetierException("PERIODE_INVALIDE", "La date de début doit précéder la date de fin.");
        var fmt = DateTimeFormatter.ofPattern("ddMMyyyy");
        resumer(requete, "Synthèse Excel du " + d + " au " + f);
        return fichier(rapportService.genererSyntheseExcel(d, f), "synthese_" + d.format(fmt) + "-" + f.format(fmt) + ".xlsx", XLSX);
    }

    // ── Statistiques ──

    @GetMapping("/statistiques")
    public ApiResponse<Map<String, Object>> statistiques(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin,
            HttpServletRequest requete) {
        LocalDate today = LocalDate.now();
        LocalDate d = debut != null ? debut : today.withDayOfMonth(1);
        LocalDate f = fin != null ? fin : (debut != null ? d.plusDays(6) : today.withDayOfMonth(today.lengthOfMonth()));
        if (d.isAfter(f)) throw new MetierException("PERIODE_INVALIDE", "La date de début doit précéder la date de fin.");
        if (d.plusYears(1).isBefore(f)) throw new MetierException("PERIODE_INVALIDE", "Une année au plus.");
        Map<String, Object> data = new LinkedHashMap<>(statistiquesService.computeStatistiques(d, f));
        data.keySet().removeAll(STATS_AFFICHAGE);
        resumer(requete, "Statistiques du " + d + " au " + f);
        return ApiResponse.success("Statistiques du " + d + " au " + f + ".", data);
    }

    // ── Écran d'émargement ──

    @GetMapping("/ecran")
    public ApiResponse<EcranEmargementService.Etat> ecran(HttpServletRequest requete) {
        resumer(requete, "Consultation de l'écran d'émargement");
        return ApiResponse.success("Réglages de l'écran d'émargement.", ecranEmargementService.etat());
    }

    public record Zone(String coordonnees, Integer rayon) { }

    @PostMapping("/ecran/zone")
    public ApiResponse<Map<String, Object>> reglerZone(@RequestBody Zone z,
                                                       @RequestParam(defaultValue = "false") boolean simulation,
                                                       HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        if (z == null) throw new MetierException("CHAMP_MANQUANT", "Indiquez les coordonnées « latitude, longitude ».");
        Runnable action = () -> ecranEmargementService.reglerZone(z.coordonnees(), z.rayon());
        if (simulation) {
            simulationService.simuler(() -> { action.run(); return null; });
        } else {
            action.run();
        }
        int rayon = z.rayon() != null ? z.rayon() : ci.esatic.sigep.service.ZoneEmargement.RAYON_DEFAUT;
        return fait(requete, (simulation ? "Simulation : la zone de présence serait réglée" : "Zone de présence réglée")
                + " (rayon " + rayon + " m). Un téléphone hors de ce cercle ne pourrait plus émarger.",
                simulation ? Map.of("simulation", true) : Map.of());
    }

    @PostMapping("/ecran/zone/retirer")
    public ApiResponse<Map<String, Object>> retirerZone(@RequestParam(defaultValue = "false") boolean simulation,
                                                        HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        if (simulation) {
            return fait(requete, "Simulation : le contrôle de position serait retiré ; on pourrait émarger de partout.",
                    Map.of("simulation", true));
        }
        ecranEmargementService.retirerZone();
        return fait(requete, "Contrôle de position retiré : on peut émarger de partout.", Map.of());
    }

    public record Reseau(String reseaux) { }

    @PostMapping("/ecran/reseau")
    public ApiResponse<Map<String, Object>> reglerReseau(@RequestBody Reseau r,
                                                         @RequestParam(defaultValue = "false") boolean simulation,
                                                         HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        String saisie = r == null ? null : r.reseaux();
        String propre;
        try {
            propre = simulation
                    ? simulationService.simuler(() -> ecranEmargementService.reglerReseau(saisie))
                    : ecranEmargementService.reglerReseau(saisie);
        } catch (MetierException e) {
            // Le service ne rend que l'entrée fautive ; la console complète la phrase, l'agent aussi.
            throw new MetierException(e.getCode(), "Adresse illisible : « " + e.getMessage()
                    + " ». Saisissez des adresses IP ou des plages comme 196.47.20.0/24, séparées par des virgules.");
        }
        String effet = propre.isEmpty() ? "l'écran s'afficherait partout où son cookie l'autorise"
                : "l'écran ne s'afficherait plus que depuis : " + propre;
        return fait(requete, (simulation ? "Simulation : " : "Réseau enregistré : ") + effet + ".",
                simulation ? Map.of("simulation", true, "reseaux", propre) : Map.of("reseaux", propre));
    }

    // ── Équipe ──

    @GetMapping("/equipe")
    public ApiResponse<Map<String, Object>> equipe(HttpServletRequest requete) {
        resumer(requete, "Consultation de l'équipe");
        return ApiResponse.success("Équipe de l'établissement.", Map.of("membres", equipeService.membres()));
    }

    public record Invitation(String email, String profil) { }

    @PostMapping("/equipe")
    public ApiResponse<Map<String, Object>> inviter(@RequestBody Invitation i,
                                                    @RequestParam(defaultValue = "false") boolean simulation,
                                                    HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        ERole profil = profil(i == null ? null : i.profil());
        String email = i == null ? null : i.email();
        if (simulation) {
            String adresse = equipeService.verifierInvitation(email, profil);
            return fait(requete, "Simulation : un accès « " + libelle(profil) + " » serait créé pour " + adresse
                    + ", qui recevrait ses identifiants par e-mail.", Map.of("simulation", true));
        }
        String adresse = equipeService.inviter(email, profil);
        return fait(requete, "Accès « " + libelle(profil) + " » créé : les identifiants sont envoyés à " + adresse + ".", Map.of());
    }

    // ── Logo ──

    @GetMapping("/logo")
    public ApiResponse<Map<String, Object>> logo(HttpServletRequest requete) {
        resumer(requete, "Consultation du logo");
        boolean present = logoService.aUnLogo();
        return ApiResponse.success(present ? "L'établissement a un logo." : "Pas de logo : celui de SIGEP s'affiche.",
                Map.of("present", present));
    }

    @PostMapping(value = "/logo", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> deposerLogo(@RequestParam("fichier") MultipartFile fichier,
                                                        @RequestParam(defaultValue = "false") boolean simulation,
                                                        HttpServletRequest requete) throws IOException {
        marquerSimulation(requete, simulation);
        byte[] octets = fichier.getBytes();
        if (simulation) {
            logoService.courantAvecMarque();
            logoService.verifier(octets, fichier.getContentType());
            return fait(requete, "Simulation : cette image deviendrait le logo de l'établissement.", Map.of("simulation", true));
        }
        logoService.deposer(octets, fichier.getContentType());
        return fait(requete, "Logo mis à jour.", Map.of());
    }

    @PostMapping("/logo/retirer")
    public ApiResponse<Map<String, Object>> retirerLogo(@RequestParam(defaultValue = "false") boolean simulation,
                                                        HttpServletRequest requete) {
        marquerSimulation(requete, simulation);
        if (simulation) {
            logoService.courantAvecMarque();
            return fait(requete, "Simulation : le logo serait retiré ; celui de SIGEP reprendrait sa place.", Map.of("simulation", true));
        }
        logoService.retirer();
        return fait(requete, "Logo retiré : celui de SIGEP reprend sa place.", Map.of());
    }

    // ── Abonnement ──

    @GetMapping("/abonnement")
    public ApiResponse<Map<String, Object>> abonnement(HttpServletRequest requete) {
        Etablissement e = etablissementCourantService.courant();
        if (e == null) throw new MetierException("INDISPONIBLE", "Aucun établissement rattaché à ce compte.");
        resumer(requete, "Consultation de l'abonnement");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plan", e.getPlan());
        data.put("expiration", e.getDateExpiration());
        data.put("joursRestants", abonnementService.joursAvantExpiration(e));
        data.put("accesSuspendu", abonnementService.accesSuspendu(e));
        data.put("enseignants", enseignantRepository.countByEtablissementId(e.getId()));
        data.put("maxEnseignants", e.getMaxEnseignants());
        data.put("prixMensuel", planService.prixMensuel(e.getPlan()));
        data.put("devise", planService.devise().toUpperCase(Locale.ROOT));
        return ApiResponse.success("Abonnement de l'établissement. Le paiement se fait dans la console.", data);
    }

    // ── Outils ──

    private static YearMonth mois(String valeur) {
        if (valeur == null || valeur.isBlank()) return YearMonth.now();
        try {
            YearMonth m = YearMonth.parse(valeur.trim());
            if (m.isBefore(YearMonth.of(2000, 1)) || m.isAfter(YearMonth.of(2100, 12))) throw new DateTimeParseException("", valeur, 0);
            return m;
        } catch (DateTimeParseException e) {
            throw new MetierException("MOIS_INVALIDE", "Mois attendu au format AAAA-MM (ex. 2026-11).");
        }
    }

    private static ERole profil(String valeur) {
        return switch (valeur == null ? "" : valeur.trim().toUpperCase(Locale.ROOT).replace("É", "E")) {
            case "DIRECTION" -> ERole.ROLE_DIRECTION;
            case "SCOLARITE" -> ERole.ROLE_SCOLARITE;
            case "COMPTABILITE" -> ERole.ROLE_COMPTABILITE;
            default -> throw new MetierException("PROFIL_INVALIDE", "Profil : direction, scolarite ou comptabilite.");
        };
    }

    private static String libelle(ERole profil) {
        return ci.esatic.sigep.config.ProfilsAdmin.LIBELLES.get(profil);
    }

    private static ResponseEntity<byte[]> fichier(byte[] contenu, String nom, MediaType type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nom + "\"")
                .contentType(type)
                .body(contenu);
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
