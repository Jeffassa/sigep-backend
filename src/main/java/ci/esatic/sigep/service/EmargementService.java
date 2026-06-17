package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.EmargementRequest;
import ci.esatic.sigep.dto.response.EmargementResponse;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmargementService {

    private static final int TOLERANCE_AVANT_MINUTES = 15;
    private static final int TOLERANCE_APRES_MINUTES = 30;

    private final EmargementRepository emargementRepository;
    private final SeanceRepository seanceRepository;
    private final EnseignantRepository enseignantRepository;
    private final QrCodeService qrCodeService;

    @Transactional
    public EmargementResponse emarger(Long userId, EmargementRequest request) {
        Enseignant enseignant = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "userId", userId));

        Seance seance = seanceRepository.findById(request.getSeanceId())
                .orElseThrow(() -> new ResourceNotFoundException("Seance", "id", request.getSeanceId()));

        validerRegles(enseignant, seance, request.getQrToken());
        validerSignature(request.getSignatureBase64());

        seance.setStatut(StatutSeance.EMARGE);
        seanceRepository.save(seance);

        Emargement emargement = Emargement.builder()
                .seance(seance)
                .enseignant(enseignant)
                .dateHeure(LocalDateTime.now())
                .signatureBase64(request.getSignatureBase64())
                .qrTokenUtilise(request.getQrToken())
                .build();

        emargement = emargementRepository.save(emargement);
        log.info("Emargement valide - Seance {} par Enseignant {}", seance.getId(), enseignant.getId());

        return toResponse(emargement);
    }

    public List<EmargementResponse> getHistorique(Long userId) {
        Enseignant enseignant = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "userId", userId));
        return emargementRepository.findByEnseignantId(enseignant.getId())
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    private void validerRegles(Enseignant enseignant, Seance seance, String qrToken) {
        // Regle 1 : La seance appartient bien a cet enseignant
        if (!seance.getEnseignant().getId().equals(enseignant.getId())) {
            throw new IllegalArgumentException("Cette seance ne vous appartient pas");
        }

        // Regle 2 : La seance est bien aujourd'hui
        if (!seance.getDate().equals(LocalDate.now())) {
            throw new IllegalArgumentException("Cette seance n'est pas prevue aujourd'hui");
        }

        // Regle 3 : Unicite — pas encore emargee
        if (emargementRepository.existsBySeanceId(seance.getId())) {
            throw new IllegalArgumentException("Cette seance a deja ete emargee");
        }

        // Regle 4 : Fenetre de temps valide
        LocalTime maintenant = LocalTime.now();
        LocalTime debutAutorise = seance.getHeureDebut().minusMinutes(TOLERANCE_AVANT_MINUTES);
        LocalTime finAutorisee = seance.getHeureFin().plusMinutes(TOLERANCE_APRES_MINUTES);

        if (maintenant.isBefore(debutAutorise)) {
            throw new IllegalArgumentException("Emargement impossible : la seance ne commence pas encore (ouverture dans "
                    + TOLERANCE_AVANT_MINUTES + " min avant le debut)");
        }
        if (maintenant.isAfter(finAutorisee)) {
            throw new IllegalArgumentException("Emargement impossible : delai depasse ("
                    + TOLERANCE_APRES_MINUTES + " min apres la fin de la seance)");
        }

        // Regle 5 : Token QR valide pour la bonne salle
        String salleLibelle = seance.getSalle().getLibelle();
        if (!qrCodeService.validateQrToken(qrToken, salleLibelle)) {
            throw new IllegalArgumentException("QR Code invalide ou expire pour la salle " + salleLibelle);
        }
    }

    private void validerSignature(String signatureBase64) {
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            throw new IllegalArgumentException("La signature est obligatoire pour l'emargement");
        }
        if (signatureBase64.length() > 700_000) {
            throw new IllegalArgumentException("Signature invalide (taille hors limites)");
        }
        // Format Base64 strict (peut contenir le prefixe data:image/png;base64,)
        String payload = signatureBase64.contains(",") ? signatureBase64.split(",", 2)[1] : signatureBase64;
        if (!payload.matches("^[A-Za-z0-9+/]+={0,2}$")) {
            throw new IllegalArgumentException("Signature invalide (format Base64 incorrect)");
        }
    }

    private EmargementResponse toResponse(Emargement e) {
        return EmargementResponse.builder()
                .id(e.getId())
                .seanceId(e.getSeance().getId())
                .matiereLibelle(e.getSeance().getMatiere().getLibelle())
                .classeLibelle(e.getSeance().getClasse().getLibelle())
                .salleLibelle(e.getSeance().getSalle().getLibelle())
                .dateHeure(e.getDateHeure())
                .enseignantNom(e.getEnseignant().getNom())
                .enseignantPrenom(e.getEnseignant().getPrenom())
                .build();
    }
}
