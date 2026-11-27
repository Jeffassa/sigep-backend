package ci.esatic.sigep.service.assistant;

import ci.esatic.sigep.controller.AgentEnseignantController;
import ci.esatic.sigep.controller.AgentPilotageController;
import ci.esatic.sigep.controller.AgentReferentielController;
import ci.esatic.sigep.controller.AgentSeanceController;
import ci.esatic.sigep.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Outils de l'assistant de la console : les mêmes actions que l'API d'agent, appelées sans passer
 * par HTTP. Chaque outil reprend donc les règles, la simulation et les messages de SIGEP.
 *
 * <p>Un outil qui écrit n'est jamais exécuté sur la seule décision du modèle : l'assistant en joue
 * la simulation, et l'action réelle attend le clic de l'administrateur (voir AssistantService).
 * Les fichiers (paie, rapports) ne passent pas par le modèle : il donne un lien vers la console.
 */
@Component
@RequiredArgsConstructor
public class OutilsAssistant {

    private final AgentEnseignantController enseignants;
    private final AgentSeanceController seances;
    private final AgentReferentielController referentiels;
    private final AgentPilotageController pilotage;
    private final ObjectMapper objectMapper;

    /** Ce que fait l'outil, avec les arguments rétablis (vraies valeurs). */
    @FunctionalInterface
    public interface Action {
        Object executer(Arguments a, boolean simulation, HttpServletRequest requete, User admin) throws Exception;
    }

    /**
     * @param ecriture l'outil modifie quelque chose : simulé, puis confirmé par l'administrateur
     * @param libelle  ce que montre le bouton de confirmation
     */
    public record Outil(String nom, String description, Map<String, Object> schema, boolean ecriture,
                        String libelle, Action action) { }

    /** Arguments d'un appel, lus avec le type attendu. */
    public final class Arguments {
        private final Map<String, Object> valeurs;

        Arguments(Map<String, Object> valeurs) {
            this.valeurs = valeurs == null ? Map.of() : valeurs;
        }

        public String texte(String cle) {
            Object v = valeurs.get(cle);
            return v == null ? null : String.valueOf(v);
        }

        public String texte(String cle, String defaut) {
            String v = texte(cle);
            return v == null || v.isBlank() ? defaut : v;
        }

        public Long entier(String cle) {
            Object v = valeurs.get(cle);
            if (v == null || String.valueOf(v).isBlank()) return null;
            return v instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(v).trim());
        }

        public int entier(String cle, int defaut) {
            Long v = entier(cle);
            return v == null ? defaut : v.intValue();
        }

        public boolean vrai(String cle) {
            return Boolean.parseBoolean(String.valueOf(valeurs.get(cle)));
        }

        public LocalDate date(String cle) {
            String v = texte(cle);
            return v == null || v.isBlank() ? null : LocalDate.parse(v.trim());
        }

        public List<Long> entiers(String cle) {
            Object v = valeurs.get(cle);
            if (!(v instanceof List<?> l)) return null;
            return l.stream().map(x -> x instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(x))).toList();
        }

        /** Corps de requête d'un contrôleur (record), construit depuis les arguments. */
        public <T> T corps(Class<T> type, Map<String, String> renommage) {
            Map<String, Object> source = new LinkedHashMap<>();
            valeurs.forEach((k, v) -> source.put(renommage.getOrDefault(k, k), v));
            return objectMapper.convertValue(source, type);
        }
    }

    public Arguments arguments(Map<String, Object> valeurs) {
        return new Arguments(valeurs);
    }

    // ── Catalogue ──

    public List<Outil> catalogue() {
        List<Outil> o = new ArrayList<>();

        // Enseignants
        o.add(lecture("chercher_enseignants", "Cherche des enseignants (permanents et vacataires) par nom, prénom, "
                        + "matricule, e-mail ou département. Renvoie leurs identifiants.",
                objet(prop("texte", "string", "Texte cherché ; vide pour tous"), prop("departement", "string", null),
                        prop("archives", "boolean", "Inclure ceux qui ont quitté l'établissement"),
                        prop("page", "integer", "Page, à partir de 0")),
                (a, s, r, u) -> enseignants.chercher(a.texte("texte", ""), a.texte("departement", ""), a.vrai("archives"),
                        a.entier("page", 0), 20, r)));
        o.add(lecture("fiche_enseignant", "Fiche d'un enseignant : identité, statut, compte, séances récentes.",
                objet(req(prop("enseignant_id", "integer", null))),
                (a, s, r, u) -> enseignants.fiche(a.entier("enseignant_id"), r)));
        o.add(ecriture("creer_enseignant", "Crée un enseignant (le quota du plan et l'unicité du matricule sont "
                        + "vérifiés). Avec un e-mail, ses accès lui sont envoyés.", "Créer l'enseignant",
                objet(req(prop("matricule", "string", null)), req(prop("nom", "string", null)), req(prop("prenom", "string", null)),
                        prop("email", "string", null), prop("departement", "string", null), prop("grade", "string", null)),
                (a, s, r, u) -> corps(enseignants.creer(a.corps(AgentEnseignantController.CreationEnseignant.class, Map.of()), s, r))));
        o.add(ecriture("changer_statut_enseignant", "Active (VALIDATED) ou archive (ARCHIVE) un enseignant : archivé, il ne "
                        + "peut plus se connecter.", "Changer le statut",
                objet(req(prop("enseignant_id", "integer", null)), req(enumere("statut", List.of("VALIDATED", "ARCHIVE")))),
                (a, s, r, u) -> enseignants.changerStatut(a.entier("enseignant_id"),
                        a.corps(AgentEnseignantController.ChangementStatut.class, Map.of()), s, r)));
        o.add(ecriture("renvoyer_acces", "Renvoie ses accès (nouveau mot de passe provisoire) à un enseignant par e-mail.",
                "Renvoyer les accès", objet(req(prop("enseignant_id", "integer", null))),
                (a, s, r, u) -> enseignants.renvoyerAcces(a.entier("enseignant_id"), s, r)));
        o.add(ecriture("envoyer_message", "Écrit un message aux enseignants : à tous, ou à un seul (enseignant_id).",
                "Envoyer le message",
                objet(prop("enseignant_id", "integer", "Vide : tous les enseignants actifs"),
                        req(prop("sujet", "string", null)), req(prop("corps", "string", null))),
                (a, s, r, u) -> enseignants.envoyerMessage(a.corps(AgentEnseignantController.Message.class,
                        Map.of("enseignant_id", "enseignantId")), s, r)));

        // Page Alertes, séances
        o.add(lecture("alertes", "Page Alertes : séances importées à valider, demandes de rattrapage, émargements hors "
                        + "ligne à confirmer, séances passées non émargées, comptes en attente. Donne les identifiants.",
                objet(), (a, s, r, u) -> seances.alertes(r)));
        o.add(lecture("seances_du_jour", "Séances d'un jour (aujourd'hui par défaut), avec leur état d'émargement.",
                objet(prop("date", "string", "AAAA-MM-JJ")), (a, s, r, u) -> seances.seancesDuJour(a.date("date"), r)));
        o.add(lecture("referentiels", "Matières, classes, salles et sites de l'établissement, avec leurs identifiants.",
                objet(), (a, s, r, u) -> fusion(seances.referentiels(r), referentiels.campus(r))));
        o.add(ecriture("valider_seances", "Valide des séances importées par les enseignants (une ou plusieurs).",
                "Valider les séances", objet(req(liste("ids", "Identifiants des séances"))),
                (a, s, r, u) -> seances.validerSeances(new AgentSeanceController.Lot(a.entiers("ids")), s, r)));
        o.add(ecriture("refuser_seance", "Refuse (supprime) une séance importée en attente de validation.",
                "Refuser la séance", objet(req(prop("seance_id", "integer", null))),
                (a, s, r, u) -> seances.refuserSeance(a.entier("seance_id"), s, r)));
        o.add(ecriture("planifier_seance", "Planifie une séance (enseignant, matière, classe, salle, date, heures HH:MM).",
                "Planifier la séance",
                objet(req(prop("enseignant_id", "integer", null)), req(prop("matiere_id", "integer", null)),
                        req(prop("classe_id", "integer", null)), req(prop("salle_id", "integer", null)),
                        req(prop("date", "string", "AAAA-MM-JJ")), req(prop("heure_debut", "string", "HH:MM")),
                        req(prop("heure_fin", "string", "HH:MM")), prop("rattrapage", "boolean", null)),
                (a, s, r, u) -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("enseignantId", a.entier("enseignant_id"));
                    v.put("matiereId", a.entier("matiere_id"));
                    v.put("classeId", a.entier("classe_id"));
                    v.put("salleId", a.entier("salle_id"));
                    v.put("date", a.texte("date"));
                    v.put("heureDebut", a.texte("heure_debut"));
                    v.put("heureFin", a.texte("heure_fin"));
                    v.put("type", a.vrai("rattrapage") ? "RATTRAPAGE" : "NORMALE");
                    return corps(seances.planifier(objectMapper.convertValue(v, AgentSeanceController.Planification.class), s, r));
                }));
        o.add(ecriture("traiter_rattrapage", "Accepte (avec une salle) ou refuse une demande de rattrapage.",
                "Traiter le rattrapage",
                objet(req(prop("demande_id", "integer", null)), req(prop("accepter", "boolean", null)),
                        prop("salle_id", "integer", "Obligatoire pour accepter")),
                (a, s, r, u) -> a.vrai("accepter")
                        ? seances.accepterRattrapage(a.entier("demande_id"), new AgentSeanceController.Acceptation(a.entier("salle_id")), s, r)
                        : seances.refuserRattrapage(a.entier("demande_id"), s, r)));
        o.add(ecriture("traiter_emargement_hors_ligne", "Confirme ou refuse un émargement déclaré hors ligne.",
                "Traiter l'émargement",
                objet(req(prop("emargement_id", "integer", null)), req(prop("valider", "boolean", null))),
                (a, s, r, u) -> a.vrai("valider") ? seances.validerHorsLigne(a.entier("emargement_id"), s, r)
                        : seances.refuserHorsLigne(a.entier("emargement_id"), s, r)));

        // Référentiels, calendrier
        o.add(ecriture("ajouter_referentiel", "Ajoute une matière, une classe, une salle (nom court sans espace, ex. A101) "
                        + "ou un site (campus).", "Ajouter",
                objet(req(enumere("type", List.of("matiere", "classe", "salle", "campus"))), req(prop("nom", "string", null)),
                        prop("description", "string", "Matière"), prop("filiere", "string", "Classe"),
                        prop("niveau", "integer", "Classe"), prop("batiment", "string", "Salle"),
                        prop("capacite", "integer", "Salle"), prop("campus_id", "integer", "Salle : site"),
                        prop("adresse", "string", "Site")),
                (a, s, r, u) -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("libelle", a.texte("nom"));
                    v.put("nom", a.texte("nom"));
                    v.put("description", a.texte("description"));
                    v.put("filiere", a.texte("filiere"));
                    v.put("niveau", a.entier("niveau"));
                    v.put("batiment", a.texte("batiment"));
                    v.put("capacite", a.entier("capacite"));
                    v.put("campusId", a.entier("campus_id"));
                    v.put("adresse", a.texte("adresse"));
                    return referentiels.ajouter(a.texte("type"), objectMapper.convertValue(v, AgentReferentielController.Ajout.class), s, r);
                }));
        o.add(ecriture("supprimer_referentiel", "Supprime une matière, une classe, une salle ou un site (refusé s'il sert).",
                "Supprimer",
                objet(req(enumere("type", List.of("matiere", "classe", "salle", "campus"))), req(prop("element_id", "integer", null))),
                (a, s, r, u) -> referentiels.supprimer(a.texte("type"), a.entier("element_id"), s, r)));
        o.add(lecture("calendrier", "Calendrier : fériés, vacances, semestres (avec identifiants), tolérance d'émargement.",
                objet(), (a, s, r, u) -> referentiels.calendrier(r)));
        o.add(ecriture("ajouter_periode", "Ajoute un férié, des vacances ou un semestre. retirer_seances supprime les "
                        + "séances déjà planifiées pendant une fermeture.", "Ajouter la période",
                objet(req(enumere("type", List.of("FERIE", "VACANCES", "SEMESTRE"))), req(prop("libelle", "string", null)),
                        req(prop("debut", "string", "AAAA-MM-JJ")), prop("fin", "string", "AAAA-MM-JJ ; vide pour un jour"),
                        prop("retirer_seances", "boolean", null)),
                (a, s, r, u) -> referentiels.ajouterPeriode(a.corps(AgentReferentielController.Periode.class,
                        Map.of("retirer_seances", "retirerSeances")), s, r)));
        o.add(ecriture("supprimer_periode", "Supprime une période du calendrier.", "Supprimer la période",
                objet(req(prop("periode_id", "integer", null))),
                (a, s, r, u) -> referentiels.supprimerPeriode(a.entier("periode_id"), s, r)));
        o.add(ecriture("regler_tolerance", "Règle la tolérance d'émargement, en minutes avant et après le début.",
                "Régler la tolérance", objet(req(prop("avant", "integer", null)), req(prop("apres", "integer", null))),
                (a, s, r, u) -> referentiels.tolerance(new AgentReferentielController.Tolerance(a.entier("avant", 0),
                        a.entier("apres", 0)), s, r)));
        o.add(ecriture("remplacer_enseignant", "Confie à un remplaçant les séances d'un enseignant absent sur une période "
                        + "(le remplaçant est prévenu).", "Confier les séances",
                objet(req(prop("absent_id", "integer", null)), req(prop("remplacant_id", "integer", null)),
                        req(prop("debut", "string", "AAAA-MM-JJ")), req(prop("fin", "string", "AAAA-MM-JJ"))),
                (a, s, r, u) -> referentiels.remplacer(a.corps(AgentReferentielController.Remplacement.class,
                        Map.of("absent_id", "absentId", "remplacant_id", "remplacantId")), s, r)));

        // Paie, rapports, statistiques
        o.add(lecture("paie", "Heures du mois par enseignant : payables, non émargées, en attente, à venir. Aucun montant.",
                objet(prop("mois", "string", "AAAA-MM ; vide : mois en cours")),
                (a, s, r, u) -> pilotage.paie(a.texte("mois"), r)));
        o.add(lecture("lien_export_paie", "Lien de téléchargement du fichier de paie du mois (Excel ou CSV).",
                objet(prop("mois", "string", "AAAA-MM"), enumere("format", List.of("xlsx", "csv"))),
                (a, s, r, u) -> lien("Fichier de paie", UriComponentsBuilder.fromPath("/admin/paie/export." + a.texte("format", "xlsx"))
                        .queryParamIfPresent("mois", java.util.Optional.ofNullable(a.texte("mois"))).build().toUriString())));
        o.add(lecture("rapports", "Rapports PDF déjà générés (identifiant, enseignant, période, taux).",
                objet(prop("enseignant_id", "integer", null), prop("debut", "string", "AAAA-MM-JJ"), prop("fin", "string", "AAAA-MM-JJ")),
                (a, s, r, u) -> pilotage.rapports(a.entier("enseignant_id"), a.date("debut"), a.date("fin"), r)));
        o.add(ecriture("generer_rapports", "Génère un rapport PDF par enseignant sur une période.", "Générer les rapports",
                objet(req(prop("debut", "string", "AAAA-MM-JJ")), req(prop("fin", "string", "AAAA-MM-JJ"))),
                (a, s, r, u) -> pilotage.generer(new AgentPilotageController.Generation(a.date("debut"), a.date("fin")), s, r)));
        o.add(lecture("lien_rapport", "Lien de téléchargement d'un rapport PDF.", objet(req(prop("rapport_id", "integer", null))),
                (a, s, r, u) -> lien("Rapport " + a.entier("rapport_id"), "/admin/rapports/" + a.entier("rapport_id") + "/telecharger")));
        o.add(lecture("lien_synthese", "Lien de téléchargement de la synthèse Excel d'une période (une ligne par enseignant).",
                objet(prop("debut", "string", "AAAA-MM-JJ"), prop("fin", "string", "AAAA-MM-JJ")),
                (a, s, r, u) -> lien("Synthèse Excel", UriComponentsBuilder.fromPath("/admin/rapports/synthese")
                        .queryParamIfPresent("debut", java.util.Optional.ofNullable(a.texte("debut")))
                        .queryParamIfPresent("fin", java.util.Optional.ofNullable(a.texte("fin"))).build().toUriString())));
        o.add(lecture("statistiques", "Statistiques d'émargement d'une période (mois en cours par défaut).",
                objet(prop("debut", "string", "AAAA-MM-JJ"), prop("fin", "string", "AAAA-MM-JJ")),
                (a, s, r, u) -> pilotage.statistiques(a.date("debut"), a.date("fin"), r)));

        // Écran, équipe, abonnement
        o.add(lecture("ecran", "Réglages de l'écran d'émargement : zone de présence et réseaux autorisés.",
                objet(), (a, s, r, u) -> pilotage.ecran(r)));
        o.add(ecriture("regler_zone", "Règle la zone de présence (centre « latitude, longitude », rayon 50 à 2000 m), "
                        + "ou la retire (retirer=true).", "Régler la zone",
                objet(prop("coordonnees", "string", "« latitude, longitude »"), prop("rayon", "integer", "Mètres"),
                        prop("retirer", "boolean", null)),
                (a, s, r, u) -> a.vrai("retirer") ? pilotage.retirerZone(s, r)
                        : pilotage.reglerZone(new AgentPilotageController.Zone(a.texte("coordonnees"),
                        a.entier("rayon") == null ? null : a.entier("rayon").intValue()), s, r)));
        o.add(ecriture("regler_reseau", "Réseaux d'où l'écran peut s'afficher (IP ou plages CIDR, séparées par des "
                        + "virgules ; vide : partout).", "Régler le réseau", objet(req(prop("reseaux", "string", null))),
                (a, s, r, u) -> pilotage.reglerReseau(new AgentPilotageController.Reseau(a.texte("reseaux", "")), s, r)));
        o.add(lecture("equipe", "Équipe de l'établissement et état de chaque accès.", objet(), (a, s, r, u) -> pilotage.equipe(r)));
        o.add(ecriture("inviter_membre", "Donne un accès à la console : profil direction, scolarite ou comptabilite. "
                        + "La personne reçoit ses identifiants par e-mail.", "Inviter",
                objet(req(prop("email", "string", null)), req(enumere("profil", List.of("direction", "scolarite", "comptabilite")))),
                (a, s, r, u) -> pilotage.inviter(new AgentPilotageController.Invitation(a.texte("email"), a.texte("profil")), s, r)));
        o.add(lecture("abonnement", "Abonnement : plan, échéance, enseignants comptés. Le paiement se fait dans la console.",
                objet(), (a, s, r, u) -> pilotage.abonnement(r)));
        return o;
    }

    public Map<String, Outil> parNom() {
        return catalogue().stream().collect(Collectors.toMap(Outil::nom, Function.identity(), (x, y) -> x, LinkedHashMap::new));
    }

    // ── Aides ──

    private static Outil lecture(String nom, String description, Map<String, Object> schema, Action action) {
        return new Outil(nom, description, schema, false, null, action);
    }

    private static Outil ecriture(String nom, String description, String libelle, Map<String, Object> schema, Action action) {
        return new Outil(nom, description + " Écriture : SIGEP la simule, l'administrateur confirme d'un clic.",
                schema, true, libelle, action);
    }

    private static Object corps(ResponseEntity<?> reponse) {
        return reponse.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Object fusion(Object a, Object b) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a instanceof ci.esatic.sigep.dto.response.ApiResponse<?> ra && ra.getData() instanceof Map<?, ?> da) m.putAll((Map<String, Object>) da);
        if (b instanceof ci.esatic.sigep.dto.response.ApiResponse<?> rb && rb.getData() instanceof Map<?, ?> db) m.putAll((Map<String, Object>) db);
        return ci.esatic.sigep.dto.response.ApiResponse.success("Référentiels et sites.", m);
    }

    /** Un fichier ne passe pas par le modèle : l'administrateur le télécharge dans la console. */
    private static Map<String, Object> lien(String libelle, String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lien", url);
        m.put("libelle", libelle);
        m.put("message", "Lien affiché à l'administrateur sous ta réponse ; ne le recopie pas.");
        return m;
    }

    // Schémas JSON

    private record Propriete(String nom, Map<String, Object> schema, boolean requise) { }

    private static Propriete prop(String nom, String type, String description) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        if (description != null) s.put("description", description);
        return new Propriete(nom, s, false);
    }

    private static Propriete enumere(String nom, List<String> valeurs) {
        return new Propriete(nom, Map.of("type", "string", "enum", valeurs), false);
    }

    private static Propriete liste(String nom, String description) {
        return new Propriete(nom, Map.of("type", "array", "items", Map.of("type", "integer"), "description", description), false);
    }

    private static Propriete req(Propriete p) {
        return new Propriete(p.nom(), p.schema(), true);
    }

    private static Map<String, Object> objet(Propriete... proprietes) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> requises = new ArrayList<>();
        for (Propriete p : proprietes) {
            props.put(p.nom(), p.schema());
            if (p.requise()) requises.add(p.nom());
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", requises);
        return s;
    }
}
