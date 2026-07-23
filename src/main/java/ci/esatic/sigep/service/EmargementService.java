package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.EmargementRequest;
import ci.esatic.sigep.dto.request.EmargementHorsLigneRequest;
import ci.esatic.sigep.dto.response.EmargementResponse;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.*;
import ci.esatic.sigep.security.QrReplayGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmargementService {

    /** Fuseau de repli si l'établissement n'en définit pas (comportement historique CI/UTC+0). */
    private static final String FUSEAU_DEFAUT = "Africa/Abidjan";

    private final EmargementRepository emargementRepository;
    private final SeanceRepository seanceRepository;
    private final EnseignantRepository enseignantRepository;
    private final EtablissementRepository etablissementRepository;
    private final QrCodeService qrCodeService;
    private final QrReplayGuard qrReplayGuard;

    @Transactional
    public EmargementResponse emarger(Long userId, EmargementRequest request) {
        Enseignant enseignant = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "userId", userId));

        Seance seance = seanceRepository.findById(request.getSeanceId())
                .orElseThrow(() -> new ResourceNotFoundException("Seance", "id", request.getSeanceId()));

        Etablissement etab = etablissementDe(enseignant);
        boolean enRetard = validerRegles(enseignant, seance, request.getQrToken(), etab);
        validerSignature(request.getSignatureBase64());

        seance.setStatut(StatutSeance.EMARGE);
        seanceRepository.save(seance);

        Emargement emargement = Emargement.builder()
                .seance(seance)
                .enseignant(enseignant)
                .dateHeure(LocalDateTime.now(zoneDe(etab)))
                .enRetard(enRetard)
                .signatureBase64(request.getSignatureBase64())
                .qrTokenUtilise(request.getQrToken())
                .build();

        emargement = emargementRepository.save(emargement);
        log.info("Emargement valide - Seance {} par Enseignant {}", seance.getId(), enseignant.getId());

        return toResponse(emargement);
    }

    /** Émargement hors-ligne (file d'attente, envoyé au retour du réseau) : sans QR, marqué horsLigne. */
    @Transactional
    public EmargementResponse emargerHorsLigne(Long userId, EmargementHorsLigneRequest request) {
        Enseignant enseignant = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "userId", userId));
        Seance seance = seanceRepository.findById(request.getSeanceId())
                .orElseThrow(() -> new ResourceNotFoundException("Seance", "id", request.getSeanceId()));

        Etablissement etab = etablissementDe(enseignant);
        // Pas de vérification QR (présence non confirmée) → on garde les règles 1 à 4.
        boolean enRetard = validerReglesCommunes(enseignant, seance, etab);
        validerSignature(request.getSignatureBase64());

        seance.setStatut(StatutSeance.EMARGE);
        seanceRepository.save(seance);

        Emargement emargement = Emargement.builder()
                .seance(seance)
                .enseignant(enseignant)
                .dateHeure(LocalDateTime.now(zoneDe(etab)))
                .enRetard(enRetard)
                .horsLigne(true)
                .signatureBase64(request.getSignatureBase64())
                .build();

        emargement = emargementRepository.save(emargement);
        log.info("Emargement HORS-LIGNE - Seance {} par Enseignant {}", seance.getId(), enseignant.getId());
        return toResponse(emargement);
    }

    public List<EmargementResponse> getHistorique(Long userId) {
        Enseignant enseignant = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "userId", userId));
        return emargementRepository.findByEnseignantId(enseignant.getId())
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    /** Établissement (tenant) de l'enseignant, pour ses réglages (fuseau, tolérances). */
    private Etablissement etablissementDe(Enseignant enseignant) {
        return enseignant.getEtablissementId() == null ? null
                : etablissementRepository.findById(enseignant.getEtablissementId()).orElse(null);
    }

    /** Fuseau horaire du tenant (E1), avec repli sûr si absent/invalide. */
    private ZoneId zoneDe(Etablissement etab) {
        String tz = (etab != null && etab.getFuseau() != null && !etab.getFuseau().isBlank())
                ? etab.getFuseau() : FUSEAU_DEFAUT;
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneId.of(FUSEAU_DEFAUT);
        }
    }

    /** Règles communes (appartenance, jour, unicité, fenêtre horaire). Renvoie true si tardif. */
    private boolean validerReglesCommunes(Enseignant enseignant, Seance seance, Etablissement etab) {
        // Regle 1 : La seance appartient bien a cet enseignant
        if (!seance.getEnseignant().getId().equals(enseignant.getId())) {
            throw new MetierException("SEANCE_NON_ATTRIBUEE", "Cette seance ne vous appartient pas");
        }

        // Fuseau + tolérances PROPRES à l'établissement (E1 + E7).
        ZoneId zone = zoneDe(etab);
        int toleranceAvant = etab != null ? etab.getToleranceAvantMinutes() : 15;
        int toleranceApres = etab != null ? etab.getToleranceApresMinutes() : 30;

        // Regle 2 : La seance est bien aujourd'hui (dans le fuseau du tenant)
        if (!seance.getDate().equals(LocalDate.now(zone))) {
            throw new MetierException("PAS_AUJOURDHUI", "Cette seance n'est pas prevue aujourd'hui");
        }

        // Regle 3 : Unicite — pas encore emargee
        if (emargementRepository.existsBySeanceId(seance.getId())) {
            throw new MetierException("DEJA_EMARGEE", "Cette seance a deja ete emargee");
        }

        // Regle 4 : la seance doit avoir commence. On autorise l'emargement APRES
        // la fin (rattrapage d'oubli, le jour meme) ; il sera simplement marque "en retard".
        LocalTime maintenant = LocalTime.now(zone);
        LocalTime debutAutorise = seance.getHeureDebut().minusMinutes(toleranceAvant);
        LocalTime finAutorisee = seance.getHeureFin().plusMinutes(toleranceApres);

        if (maintenant.isBefore(debutAutorise)) {
            java.time.format.DateTimeFormatter hf = java.time.format.DateTimeFormatter.ofPattern("HH'h'mm");
            throw new MetierException("TROP_TOT", "Trop tot : l'emargement ouvre a " + debutAutorise.format(hf)
                    + " (la seance commence a " + seance.getHeureDebut().format(hf) + ").");
        }

        return maintenant.isAfter(finAutorisee);
    }

    /** Règles d'émargement EN LIGNE : communes + QR frais + appartenance tenant + anti-rejeu. */
    private boolean validerRegles(Enseignant enseignant, Seance seance, String qrToken, Etablissement etab) {
        boolean enRetard = validerReglesCommunes(enseignant, seance, etab);

        // Lecture UNIQUE du QR (une seule vérification de signature pour les 3 contrôles).
        ci.esatic.sigep.security.JwtService.QrUniversel qr = qrCodeService.lireQrUniversel(qrToken);

        // Regle 5 : Token QR universel valide et frais (preuve de presence)
        if (!qr.valide()) {
            throw new MetierException("QR_INVALIDE", "QR Code invalide ou expire. Rescannez le code affiche.");
        }

        // Regle 5bis (C3) : le QR doit être celui de l'établissement de l'enseignant.
        if (qr.etablissementId() == null || !qr.etablissementId().equals(enseignant.getEtablissementId())) {
            throw new MetierException("QR_AUTRE_ETABLISSEMENT", "Ce QR n'appartient pas a votre etablissement.");
        }

        // Regle 6 : anti-rejeu — un meme token ne peut servir qu'une fois par enseignant
        if (!qrReplayGuard.tryConsume(enseignant.getId(), qr.jti())) {
            throw new MetierException("QR_DEJA_UTILISE", "Ce QR a deja ete utilise. Rescannez le code affiche.");
        }

        return enRetard;
    }

    private void validerSignature(String signatureBase64) {
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            throw new MetierException("SIGNATURE_MANQUANTE", "La signature est obligatoire pour l'emargement");
        }
        if (signatureBase64.length() > 700_000) {
            throw new MetierException("SIGNATURE_INVALIDE", "Signature invalide (taille hors limites)");
        }
        // Format Base64 strict (peut contenir le prefixe data:image/png;base64,)
        String payload = signatureBase64.contains(",") ? signatureBase64.split(",", 2)[1] : signatureBase64;
        if (!payload.matches("^[A-Za-z0-9+/]+={0,2}$")) {
            throw new MetierException("SIGNATURE_INVALIDE", "Signature invalide (format Base64 incorrect)");
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
                .enRetard(e.isEnRetard())
                .horsLigne(e.isHorsLigne())
                .enseignantNom(e.getEnseignant().getNom())
                .enseignantPrenom(e.getEnseignant().getPrenom())
                .build();
    }
}
