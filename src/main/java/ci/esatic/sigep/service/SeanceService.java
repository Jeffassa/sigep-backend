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
                .filter(s -> s.getStatut() != StatutSeance.EMARGE)
                .map(this::toResponse).collect(Collectors.toList());
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

    // Création depuis un SeanceRequest (endpoint admin)
    @Transactional
    public SeanceResponse create(SeanceRequest request) {
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
        return SeanceResponse.builder()
                .id(s.getId())
                .date(s.getDate())
                .heureDebut(s.getHeureDebut())
                .heureFin(s.getHeureFin())
                .matiereLibelle(s.getMatiere().getLibelle())
                .classeLibelle(s.getClasse().getLibelle())
                .salleLibelle(s.getSalle().getLibelle())
                .salleBatiment(s.getSalle().getBatiment())
                .enseignantNom(s.getEnseignant().getNom())
                .enseignantPrenom(s.getEnseignant().getPrenom())
                .type(s.getType())
                .statut(s.getStatut())
                .build();
    }
}
