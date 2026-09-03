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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final ci.esatic.sigep.mapper.EmargementMapper emargementMapper;

    // C2 : plafond mensuel d'émargements hors-ligne par enseignant + ancienneté max d'une séance
    // synchronisable. Valeurs par défaut inline (utilisées aussi par les tests unitaires sans Spring).
    @Value("${app.emargement.hors-ligne.max-par-mois:5}")
    private int horsLigneMaxParMois = 5;
    @Value("${app.emargement.hors-ligne.max-delai-jours:2}")
    private int horsLigneMaxDelaiJours = 2;

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

    /**
     * Émargement hors-ligne (file d'attente, synchronisé au retour du réseau). La présence est
     * prouvée par le QR scanné pendant la séance (signature + établissement + fenêtre horaire via
     * l'iat signé + anti-rejeu), mais reste À CONFIRMER par l'admin : la séance passe
     * {@code EN_ATTENTE_VALIDATION} et l'émargement est marqué {@code valide=false} jusqu'à validation.
     */
    @Transactional
    public EmargementResponse emargerHorsLigne(Long userId, EmargementHorsLigneRequest request) {
        Enseignant enseignant = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "userId", userId));
        Seance seance = seanceRepository.findById(request.getSeanceId())
                .orElseThrow(() -> new ResourceNotFoundException("Seance", "id", request.getSeanceId()));

        Etablissement etab = etablissementDe(enseignant);
        ZoneId zone = zoneDe(etab);

        validerSignature(request.getSignatureBase64());

        // Règle 1 : la séance appartient bien à cet enseignant.
        if (!seance.getEnseignant().getId().equals(enseignant.getId())) {
            throw new MetierException("SEANCE_NON_ATTRIBUEE", "Cette seance ne vous appartient pas");
        }
        // Règle 3 : unicité — pas déjà émargée (ni en attente).
        if (emargementRepository.existsBySeanceId(seance.getId())) {
            throw new MetierException("DEJA_EMARGEE", "Cette seance a deja ete emargee");
        }
        // Borne d'écart séance/synchro : refuser une séance trop ancienne (ou dans le futur).
        LocalDate aujourdhui = LocalDate.now(zone);
        if (seance.getDate().isAfter(aujourdhui)
                || seance.getDate().isBefore(aujourdhui.minusDays(horsLigneMaxDelaiJours))) {
            throw new MetierException("SYNC_TROP_TARD",
                    "Emargement hors-ligne impossible : la seance est trop ancienne (max "
                            + horsLigneMaxDelaiJours + " jour(s)).");
        }

        // Preuve de présence : QR OBLIGATOIRE, vérifié à la synchro (signature + type ; expiration tolérée).
        var qr = qrCodeService.lireQrUniverselDiffere(request.getQrToken());
        if (!qr.signatureEtTypeValides() || qr.emisLe() == null) {
            throw new MetierException("QR_INVALIDE",
                    "QR invalide : impossible de confirmer la presence hors-ligne. Rescannez le code affiche.");
        }
        // Règle 5bis (C3) : le QR doit être celui de l'établissement de l'enseignant.
        if (qr.etablissementId() == null || !qr.etablissementId().equals(enseignant.getEtablissementId())) {
            throw new MetierException("QR_AUTRE_ETABLISSEMENT", "Ce QR n'appartient pas a votre etablissement.");
        }
        // Le QR doit avoir été émis PENDANT la fenêtre de la séance (preuve de présence différée).
        boolean enRetard = verifierFenetreScan(seance, etab, qr.emisLe(), zone);
        // Règle 6 : anti-rejeu — un même QR ne sert qu'une fois par enseignant.
        if (!qrReplayGuard.tryConsume(enseignant.getId(), qr.jti())) {
            throw new MetierException("QR_DEJA_UTILISE", "Ce QR a deja ete utilise. Rescannez le code affiche.");
        }
        // Plafond par enseignant sur le mois de la séance (+ alerte au dépassement).
        verifierPlafondHorsLigne(enseignant, seance);

        // Présence NON confirmée → séance en attente de validation admin (pas EMARGE).
        seance.setStatut(StatutSeance.EN_ATTENTE_VALIDATION);
        seanceRepository.save(seance);

        Emargement emargement = Emargement.builder()
                .seance(seance)
                .enseignant(enseignant)
                .dateHeure(LocalDateTime.now(zone))
                .enRetard(enRetard)
                .horsLigne(true)
                .valide(false)
                .signatureBase64(request.getSignatureBase64())
                .qrTokenUtilise(request.getQrToken())
                .build();

        emargement = emargementRepository.save(emargement);
        log.warn("Emargement HORS-LIGNE en attente de validation - Seance {} par Enseignant {}",
                seance.getId(), enseignant.getId());
        return toResponse(emargement);
    }

    /**
     * Vérifie que le QR a été émis (iat signé) le jour de la séance ET dans sa fenêtre horaire.
     * Renvoie true si le scan est postérieur à la fin de la séance (émargement « en retard »).
     */
    private boolean verifierFenetreScan(Seance seance, Etablissement etab, Instant emisLe, ZoneId zone) {
        int toleranceAvant = etab != null ? etab.getToleranceAvantMinutes() : 15;
        int toleranceApres = etab != null ? etab.getToleranceApresMinutes() : 30;
        LocalDateTime scan = LocalDateTime.ofInstant(emisLe, zone);
        if (!scan.toLocalDate().equals(seance.getDate())) {
            throw new MetierException("QR_HORS_SEANCE", "Le QR n'a pas ete scanne le jour de la seance.");
        }
        LocalTime t = scan.toLocalTime();
        LocalTime debutAutorise = seance.getHeureDebut().minusMinutes(toleranceAvant);
        LocalTime finAutorisee = seance.getHeureFin().plusMinutes(toleranceApres);
        if (t.isBefore(debutAutorise) || t.isAfter(finAutorisee)) {
            throw new MetierException("QR_HORS_FENETRE",
                    "Le QR a ete scanne hors de la fenetre autorisee de la seance.");
        }
        return t.isAfter(seance.getHeureFin());
    }

    /** Plafond mensuel d'émargements hors-ligne par enseignant (défense anti-abus + alerte log). */
    private void verifierPlafondHorsLigne(Enseignant enseignant, Seance seance) {
        LocalDate d = seance.getDate();
        long deja = emargementRepository.countHorsLigneByEnseignantEtPeriode(
                enseignant.getId(), d.withDayOfMonth(1), d.withDayOfMonth(d.lengthOfMonth()));
        if (deja >= horsLigneMaxParMois) {
            log.warn("PLAFOND hors-ligne atteint - Enseignant {} : {} sur le mois de {}",
                    enseignant.getId(), deja, d);
            throw new MetierException("HORS_LIGNE_PLAFOND",
                    "Plafond d'emargements hors-ligne atteint ce mois-ci (" + horsLigneMaxParMois
                            + "). Contactez la scolarite.");
        }
    }

    /** Admin : valide un émargement hors-ligne en attente → la séance devient émargée (comptée). */
    @Transactional
    public void validerHorsLigne(Long emargementId) {
        Emargement e = emargementRepository.findById(emargementId)
                .orElseThrow(() -> new ResourceNotFoundException("Emargement", "id", emargementId));
        if (!e.isHorsLigne() || e.isValide()) {
            throw new MetierException("ETAT_INVALIDE", "Cet emargement n'est pas en attente de validation.");
        }
        e.setValide(true);
        e.getSeance().setStatut(StatutSeance.EMARGE);
        seanceRepository.save(e.getSeance());
        emargementRepository.save(e);
        log.info("Emargement HORS-LIGNE valide par l'admin - Seance {} Enseignant {}",
                e.getSeance().getId(), e.getEnseignant().getId());
    }

    /** Admin : refuse un émargement hors-ligne en attente → la séance redevient à faire (record supprimé). */
    @Transactional
    public void refuserHorsLigne(Long emargementId) {
        Emargement e = emargementRepository.findById(emargementId)
                .orElseThrow(() -> new ResourceNotFoundException("Emargement", "id", emargementId));
        if (!e.isHorsLigne() || e.isValide()) {
            throw new MetierException("ETAT_INVALIDE", "Cet emargement n'est pas en attente de validation.");
        }
        Seance seance = e.getSeance();
        seance.setStatut(StatutSeance.A_FAIRE);
        seanceRepository.save(seance);
        log.warn("Emargement HORS-LIGNE REFUSE par l'admin - Seance {} Enseignant {} (supprime)",
                seance.getId(), e.getEnseignant().getId());
        emargementRepository.delete(e);
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
        // Signature OPTIONNELLE : la preuve de présence est le QR (anti-fraude principal, cf. C2).
        // Si une signature est fournie, on en valide le format ; absente, l'émargement reste valide.
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            return;
        }
        if (signatureBase64.length() > 700_000) {
            throw new MetierException("SIGNATURE_INVALIDE", "Signature invalide (taille hors limites)");
        }
        // On valide la chaîne ENTIÈRE (préfixe data-URI COMPRIS) : soit un payload base64 pur,
        // soit un data-URI image complet. Empêche de stocker un préfixe arbitraire non maîtrisé
        // (ex. "<img onerror=...>,<payload>") — durcissement défense-en-profondeur (audit).
        if (!signatureBase64.matches("^(data:image/(png|jpe?g);base64,)?[A-Za-z0-9+/]+={0,2}$")) {
            throw new MetierException("SIGNATURE_INVALIDE", "Signature invalide (format Base64 incorrect)");
        }
    }

    private EmargementResponse toResponse(Emargement e) {
        return emargementMapper.toResponse(e);
    }
}
