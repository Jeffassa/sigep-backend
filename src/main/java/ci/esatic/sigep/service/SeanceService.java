package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.SeanceRequest;
import ci.esatic.sigep.dto.response.SeanceResponse;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SeanceService {

    private final SeanceRepository seanceRepository;
    private final EnseignantRepository enseignantRepository;
    private final MatiereRepository matiereRepository;
    private final ClasseRepository classeRepository;
    private final SalleRepository salleRepository;
    private final ci.esatic.sigep.mapper.SeanceMapper seanceMapper;

    public List<SeanceResponse> getSeancesJour(Long enseignantId, LocalDate date) {
        return seanceRepository
                .findByEnseignantIdAndDateOrderByHeureDebutAsc(enseignantId, date)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    /** Séances du jour encore à émarger (en cours + passées non émargées). */
    public List<SeanceResponse> getSeancesAEmarger(Long enseignantId, LocalDate date) {
        return seanceRepository
                .findByEnseignantIdAndDateOrderByHeureDebutAsc(enseignantId, date)
                .stream()
                .filter(s -> s.getStatut() != StatutSeance.EMARGE && s.getStatut() != StatutSeance.A_VALIDER)
                .map(this::toResponse).collect(Collectors.toList());
    }

    // ── Séances importées par un enseignant : décision de la scolarité ──

    public List<Seance> getSeancesAValider() {
        return seanceRepository.findByStatutOrderByDateAscHeureDebutAsc(StatutSeance.A_VALIDER);
    }

    /** Accepte la séance : elle devient émargeable, et entre dans les calculs. */
    @Transactional
    public void validerImport(Long id) {
        Seance seance = seanceAValider(id);
        seance.setStatut(StatutSeance.A_FAIRE);
        seanceRepository.save(seance);
    }

    /**
     * Accepte exactement ces séances, ou aucune : si l'une n'attend plus de validation, rien
     * n'est changé. C'est la forme sûre d'un « tout valider » décidé sur une liste relue :
     * une séance arrivée entre-temps n'est pas acceptée sans avoir été vue.
     */
    @Transactional
    public int validerImports(List<Long> ids) {
        List<Seance> seances = verifierImportsEnAttente(ids);
        seances.forEach(s -> s.setStatut(StatutSeance.A_FAIRE));
        seanceRepository.saveAll(seances);
        return seances.size();
    }

    /** Vérifie, sans rien changer, que chacune de ces séances attend une validation. */
    @Transactional(readOnly = true)
    public List<Seance> verifierImportsEnAttente(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ci.esatic.sigep.exception.MetierException("CHAMP_MANQUANT", "Aucune séance indiquée.");
        }
        return ids.stream().distinct().map(this::seanceAValider).toList();
    }

    /** Accepte d'un coup toutes les séances en attente de l'établissement courant. */
    @Transactional
    public int validerTousLesImports() {
        List<Seance> seances = getSeancesAValider();
        seances.forEach(s -> s.setStatut(StatutSeance.A_FAIRE));
        seanceRepository.saveAll(seances);
        return seances.size();
    }

    /** Écarte la séance. Elle n'a pu être ni émargée ni comptée : rien ne dépend d'elle. */
    @Transactional
    public void refuserImport(Long id) {
        seanceRepository.delete(seanceAValider(id));
    }

    /** La séance, si elle attend bien une validation ; sinon un refus explicite. Sans effet. */
    @Transactional(readOnly = true)
    public Seance seanceAValider(Long id) {
        Seance seance = seanceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Seance", "id", id));
        if (seance.getStatut() != StatutSeance.A_VALIDER) {
            throw new ci.esatic.sigep.exception.MetierException("ETAT_INVALIDE",
                    "Cette séance n'attend plus de validation.");
        }
        return seance;
    }

    public List<SeanceResponse> getSeancesSemaine(Long enseignantId, LocalDate date) {
        LocalDate lundi = date.with(DayOfWeek.MONDAY);
        LocalDate samedi = lundi.plusDays(5);
        return seanceRepository
                .findByEnseignantIdAndDateBetween(enseignantId, lundi, samedi)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public SeanceResponse getById(Long id) {
        return toResponse(seanceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Seance", "id", id)));
    }

    /**
     * SECURITE (anti-IDOR) : détail d'une séance réservé à SON enseignant. Renvoie 404 si la
     * séance appartient à un autre enseignant — on ne révèle pas l'existence de la séance d'un
     * collègue. Le filtre tenant isole déjà par établissement ; ceci ajoute l'isolation par
     * enseignant à l'intérieur d'un même établissement.
     */
    public SeanceResponse getByIdPourEnseignant(Long id, Long enseignantId) {
        Seance seance = seanceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Seance", "id", id));
        if (seance.getEnseignant() == null || !seance.getEnseignant().getId().equals(enseignantId)) {
            throw new ResourceNotFoundException("Seance", "id", id);
        }
        return toResponse(seance);
    }

    // Vue admin : toutes les séances de tous les enseignants
    public List<SeanceResponse> getSeancesAdminJour(LocalDate date) {
        return seanceRepository.findAllByDateOrderByHeureDebutAsc(date)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<SeanceResponse> getSeancesAdminSemaine(LocalDate date) {
        LocalDate lundi = date.with(DayOfWeek.MONDAY);
        LocalDate samedi = lundi.plusDays(5);
        return seanceRepository.findAllByDateBetweenOrdered(lundi, samedi)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * Vérifie, sans rien écrire, qu'une séance peut être planifiée : enseignant, matière, classe
     * et salle existent dans l'établissement, et l'heure de fin suit l'heure de début.
     */
    @Transactional(readOnly = true)
    public void verifierCreation(SeanceRequest request) {
        if (request.getDate() == null || request.getHeureDebut() == null || request.getHeureFin() == null) {
            throw new ci.esatic.sigep.exception.MetierException("CHAMP_MANQUANT",
                    "Il faut la date, l'heure de début et l'heure de fin.");
        }
        if (!request.getHeureFin().isAfter(request.getHeureDebut())) {
            throw new ci.esatic.sigep.exception.MetierException("HORAIRE_INVALIDE",
                    "L'heure de fin doit suivre l'heure de début.");
        }
        if (request.getEnseignantId() == null || request.getMatiereId() == null
                || request.getClasseId() == null || request.getSalleId() == null) {
            throw new ci.esatic.sigep.exception.MetierException("CHAMP_MANQUANT",
                    "Il faut l'enseignant, la matière, la classe et la salle.");
        }
        enseignantRepository.findById(request.getEnseignantId())
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "id", request.getEnseignantId()));
        matiereRepository.findById(request.getMatiereId())
                .orElseThrow(() -> new ResourceNotFoundException("Matiere", "id", request.getMatiereId()));
        classeRepository.findById(request.getClasseId())
                .orElseThrow(() -> new ResourceNotFoundException("Classe", "id", request.getClasseId()));
        salleRepository.findById(request.getSalleId())
                .orElseThrow(() -> new ResourceNotFoundException("Salle", "id", request.getSalleId()));
    }

    // Création depuis un SeanceRequest (endpoint admin)
    @Transactional
    public SeanceResponse create(SeanceRequest request) {
        verifierCreation(request);
        Seance seance = Seance.builder()
                .date(request.getDate())
                .heureDebut(request.getHeureDebut())
                .heureFin(request.getHeureFin())
                .type(request.getType() != null ? request.getType() : TypeSeance.NORMALE)
                .build();
        return create(request.getEnseignantId(), request.getMatiereId(),
                request.getClasseId(), request.getSalleId(), seance);
    }

    @Transactional
    public SeanceResponse create(Long enseignantId, Long matiereId, Long classeId,
                                  Long salleId, Seance seance) {
        Enseignant enseignant = enseignantRepository.findById(enseignantId)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "id", enseignantId));
        Matiere matiere = matiereRepository.findById(matiereId)
                .orElseThrow(() -> new ResourceNotFoundException("Matiere", "id", matiereId));
        Classe classe = classeRepository.findById(classeId)
                .orElseThrow(() -> new ResourceNotFoundException("Classe", "id", classeId));
        Salle salle = salleRepository.findById(salleId)
                .orElseThrow(() -> new ResourceNotFoundException("Salle", "id", salleId));

        seance.setEnseignant(enseignant);
        seance.setMatiere(matiere);
        seance.setClasse(classe);
        seance.setSalle(salle);
        seance.setStatut(StatutSeance.A_FAIRE);

        return toResponse(seanceRepository.save(seance));
    }

    public SeanceResponse toResponse(Seance s) {
        return seanceMapper.toResponse(s);
    }
}
