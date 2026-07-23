package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.RattrapageRequest;
import ci.esatic.sigep.dto.response.RattrapageResponse;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RattrapageService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HEURE_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final DemandeRattrapageRepository demandeRepository;
    private final EnseignantRepository enseignantRepository;
    private final MatiereRepository matiereRepository;
    private final ClasseRepository classeRepository;
    private final SeanceRepository seanceRepository;
    private final MailService mailService;
    private final EtablissementCourantService etablissementCourantService;
    private final ci.esatic.sigep.mapper.RattrapageMapper rattrapageMapper;

    @Transactional
    public RattrapageResponse creerDemande(Long userId, RattrapageRequest request) {
        Enseignant enseignant = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "userId", userId));

        // Récupère la séance pour en déduire la matière et la classe
        Seance seance = seanceRepository.findById(request.getSeanceId())
                .orElseThrow(() -> new ResourceNotFoundException("Seance", "id", request.getSeanceId()));

        DemandeRattrapage demande = DemandeRattrapage.builder()
                .enseignant(enseignant)
                .matiere(seance.getMatiere())
                .classe(seance.getClasse())
                .dateSouhaitee(request.getDateSouhaitee())
                .heureSouhaitee(request.getHeureSouhaitee())
                .motif(request.getMotif())
                .statut(StatutDemande.EN_ATTENTE)
                .build();

        return toResponse(demandeRepository.save(demande));
    }

    public List<RattrapageResponse> getMesDemandes(Long userId) {
        Enseignant enseignant = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "userId", userId));
        return demandeRepository.findByEnseignantIdOrderByDateCreationDesc(enseignant.getId())
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<RattrapageResponse> getAllDemandes() {
        return demandeRepository.findAllByOrderByDateCreationDesc()
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<RattrapageResponse> getDemandesEnAttente() {
        return demandeRepository.findByStatutOrderByDateCreationDesc(StatutDemande.EN_ATTENTE)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public RattrapageResponse accepterAvecSalle(Long demandeId, ci.esatic.sigep.entity.Salle salle) {
        DemandeRattrapage demande = demandeRepository.findById(demandeId)
                .orElseThrow(() -> new ResourceNotFoundException("Demande", "id", demandeId));

        if (demande.getStatut() != StatutDemande.EN_ATTENTE) {
            throw new IllegalArgumentException("Cette demande a deja ete traitee");
        }

        Seance seanceRattrapage = Seance.builder()
                .date(demande.getDateSouhaitee())
                .heureDebut(demande.getHeureSouhaitee())
                .heureFin(demande.getHeureSouhaitee().plusHours(2))
                .matiere(demande.getMatiere())
                .classe(demande.getClasse())
                .enseignant(demande.getEnseignant())
                .salle(salle)
                .type(TypeSeance.RATTRAPAGE)
                .statut(StatutSeance.A_FAIRE)
                .build();

        seanceRattrapage = seanceRepository.save(seanceRattrapage);
        demande.setStatut(StatutDemande.ACCEPTE);
        demande.setSeanceRattrapage(seanceRattrapage);

        DemandeRattrapage saved = demandeRepository.save(demande);
        notifierDecision(saved, true);
        return toResponse(saved);
    }

    @Transactional
    public RattrapageResponse refuser(Long demandeId) {
        DemandeRattrapage demande = demandeRepository.findById(demandeId)
                .orElseThrow(() -> new ResourceNotFoundException("Demande", "id", demandeId));

        if (demande.getStatut() != StatutDemande.EN_ATTENTE) {
            throw new IllegalArgumentException("Cette demande a deja ete traitee");
        }

        demande.setStatut(StatutDemande.REFUSE);
        DemandeRattrapage saved = demandeRepository.save(demande);
        notifierDecision(saved, false);
        return toResponse(saved);
    }

    private void notifierDecision(DemandeRattrapage d, boolean accepte) {
        Enseignant ens = d.getEnseignant();
        String email = (ens != null && ens.getUser() != null) ? ens.getUser().getEmail() : null;
        if (email == null) return;
        String matiere = d.getMatiere() != null ? d.getMatiere().getLibelle() : "";
        String quand = d.getDateSouhaitee().format(DATE_FMT) + " à " + d.getHeureSouhaitee().format(HEURE_FMT);
        var etab = etablissementCourantService.courant();
        String expediteur = etab != null ? etab.getEmailFrom() : null;
        mailService.notifierDecisionRattrapage(expediteur, email, ens.getPrenom(), matiere, quand, accepte);
    }

    private RattrapageResponse toResponse(DemandeRattrapage d) {
        return rattrapageMapper.toResponse(d);
    }
}
