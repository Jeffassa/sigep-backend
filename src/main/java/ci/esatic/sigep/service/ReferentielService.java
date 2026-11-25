package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Campus;
import ci.esatic.sigep.entity.Classe;
import ci.esatic.sigep.entity.Matiere;
import ci.esatic.sigep.entity.Salle;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.CampusRepository;
import ci.esatic.sigep.repository.ClasseRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.MatiereRepository;
import ci.esatic.sigep.repository.SalleRepository;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Référentiels de l'établissement (matières, classes, salles, sites) et format d'emploi du temps,
 * communs à la console et à l'agent.
 *
 * <p>Les refus portent un code stable et la phrase que la console affichait déjà.
 */
@Service
@RequiredArgsConstructor
public class ReferentielService {

    public enum Type { MATIERE, CLASSE, SALLE, CAMPUS }

    public static final Set<String> FORMATS = Set.of("STANDARD", "UNIVERSITAIRE", "SECONDAIRE");

    private final MatiereRepository matiereRepository;
    private final ClasseRepository classeRepository;
    private final SalleRepository salleRepository;
    private final CampusRepository campusRepository;
    private final EtablissementRepository etablissementRepository;
    private final EtablissementCourantService etablissementCourantService;
    private final PlanService planService;
    private final SimulationService simulationService;

    @Transactional
    public Matiere creerMatiere(String libelle, String description) {
        String lib = rempli(libelle, "Le libellé de la matière est obligatoire.");
        if (matiereRepository.existsByLibelleIgnoreCase(lib)) {
            throw new MetierException("EXISTE_DEJA", "Cette matière existe déjà : " + lib);
        }
        return matiereRepository.save(Matiere.builder().libelle(lib).description(net(description)).build());
    }

    @Transactional
    public Classe creerClasse(String libelle, String filiere, Integer niveau) {
        String lib = rempli(libelle, "Le libellé de la classe est obligatoire.");
        if (classeRepository.existsByLibelleIgnoreCase(lib)) {
            throw new MetierException("EXISTE_DEJA", "Cette classe existe déjà : " + lib);
        }
        return classeRepository.save(Classe.builder().libelle(lib).filiere(net(filiere)).niveau(niveau).build());
    }

    @Transactional
    public Salle creerSalle(String libelle, String batiment, Integer capacite, Long campusId) {
        String lib = rempli(libelle, "Le nom de la salle est obligatoire.").toUpperCase();
        if (!lib.matches("[A-Z0-9_\\-]{1,20}")) {
            throw new MetierException("NOM_INVALIDE",
                    "Le nom de salle doit être court et sans espace (lettres, chiffres, - ou _). Ex : A101.");
        }
        if (salleRepository.existsByLibelleIgnoreCase(lib)) {
            throw new MetierException("EXISTE_DEJA", "Cette salle existe déjà : " + lib);
        }
        // Le campus n'est retenu que s'il appartient bien à cet établissement : le filtre
        // multi-tenant rend introuvable celui d'un autre, et l'identifiant est alors ignoré.
        Long campus = (campusId != null && campusRepository.existsById(campusId)) ? campusId : null;
        return salleRepository.save(Salle.builder().libelle(lib).batiment(net(batiment)).capacite(capacite)
                .campusId(campus).build());
    }

    @Transactional
    public Campus creerCampus(String nom, String adresse) {
        String n = rempli(nom, "Le nom de l'établissement est obligatoire.");
        if (campusRepository.existsByNomIgnoreCase(n)) {
            throw new MetierException("EXISTE_DEJA", "Cet établissement existe déjà : " + n);
        }
        // Le quota se compte AVANT l'ajout : le plan gratuit n'en autorise qu'un.
        planService.verifierQuotaCampus(etablissementCourantService.courant(), campusRepository.count());
        return campusRepository.save(Campus.builder().nom(n).adresse(net(adresse)).build());
    }

    /**
     * Supprime un élément du référentiel, s'il ne sert plus.
     *
     * <p>Une matière, une classe ou une salle utilisée par des séances est protégée par la base :
     * l'historique des heures n'a pas à disparaître. Un site qui porte encore des salles est
     * refusé avant même d'essayer, pour que l'administrateur décide où les remettre.
     *
     * @return le libellé de ce qui a été supprimé
     */
    public String supprimer(Type type, Long id) {
        String libelle = libelle(type, id);
        verifierSite(type, id);
        try {
            switch (type) {
                case MATIERE -> matiereRepository.deleteById(id);
                case CLASSE -> classeRepository.deleteById(id);
                case SALLE -> salleRepository.deleteById(id);
                case CAMPUS -> campusRepository.deleteById(id);
            }
        } catch (DataIntegrityViolationException e) {
            throw utilise(type);
        }
        return libelle;
    }

    /** Dit si la suppression passerait, sans rien supprimer. */
    public String verifierSuppression(Type type, Long id) {
        String libelle = libelle(type, id);
        verifierSite(type, id);
        try {
            simulationService.simuler(() -> {
                switch (type) {
                    case MATIERE -> matiereRepository.deleteById(id);
                    case CLASSE -> classeRepository.deleteById(id);
                    case SALLE -> salleRepository.deleteById(id);
                    case CAMPUS -> campusRepository.deleteById(id);
                }
                return null;
            });
        } catch (DataIntegrityViolationException e) {
            throw utilise(type);
        }
        return libelle;
    }

    @Transactional
    public String changerFormatPlanning(String format) {
        var etab = etablissementCourantService.courant();
        String valeur = format == null ? "STANDARD" : format.trim().toUpperCase();
        if (etab == null || !FORMATS.contains(valeur)) {
            throw new MetierException("FORMAT_INVALIDE", "Format d'emploi du temps invalide.");
        }
        etab.setFormatEmploiDuTemps(valeur);
        etablissementRepository.save(etab);
        return valeur;
    }

    private String libelle(Type type, Long id) {
        return switch (type) {
            case MATIERE -> matiereRepository.findById(id).map(Matiere::getLibelle)
                    .orElseThrow(() -> new ResourceNotFoundException("Matiere", "id", id));
            case CLASSE -> classeRepository.findById(id).map(Classe::getLibelle)
                    .orElseThrow(() -> new ResourceNotFoundException("Classe", "id", id));
            case SALLE -> salleRepository.findById(id).map(Salle::getLibelle)
                    .orElseThrow(() -> new ResourceNotFoundException("Salle", "id", id));
            case CAMPUS -> campusRepository.findById(id).map(Campus::getNom)
                    .orElseThrow(() -> new ResourceNotFoundException("Campus", "id", id));
        };
    }

    private void verifierSite(Type type, Long id) {
        if (type != Type.CAMPUS) return;
        long salles = salleRepository.countByCampusId(id);
        if (salles > 0) {
            throw new MetierException("UTILISE", "Suppression impossible : " + salles
                    + " salle(s) sont rattachées à cet établissement. Déplacez-les d'abord.");
        }
    }

    private static MetierException utilise(Type type) {
        return new MetierException("UTILISE", switch (type) {
            case MATIERE -> "Suppression impossible : cette matière est utilisée par des séances.";
            case CLASSE -> "Suppression impossible : cette classe est utilisée par des séances.";
            case SALLE -> "Suppression impossible : cette salle est utilisée par des séances.";
            case CAMPUS -> "Suppression impossible.";
        });
    }

    private static String rempli(String valeur, String message) {
        String v = valeur == null ? "" : valeur.trim();
        if (v.isEmpty()) throw new MetierException("CHAMP_MANQUANT", message);
        return v;
    }

    private static String net(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }
}
