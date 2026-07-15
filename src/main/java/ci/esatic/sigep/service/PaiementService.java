package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Paiement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.PaiementRepository;
import ci.esatic.sigep.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enregistrement UNIFIÉ d'un paiement (M6 + C7). Un seul chemin atomique et idempotent,
 * partagé par toutes les sources : Stripe, webhook Mobile Money, saisie manuelle super-admin.
 * Évite l'incohérence « plan changé mais abonnement non prolongé » (ou l'inverse).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaiementService {

    private final EtablissementRepository etablissementRepository;
    private final PaiementRepository paiementRepository;
    private final UserRepository userRepository;
    private final AbonnementService abonnementService;
    private final MailService mailService;

    /**
     * Enregistre un paiement et met à jour l'abonnement en une transaction.
     * IDEMPOTENT : si {@code reference} a déjà été traitée, ne fait rien (livraisons multiples).
     *
     * @return true si le paiement a été enregistré, false s'il était déjà traité / tenant introuvable.
     */
    @Transactional
    public boolean enregistrer(Long etablissementId, int mois, long montantFcfa, String reference, String source) {
        if (reference != null && paiementRepository.existsByReference(reference)) {
            return false; // déjà traité
        }
        Etablissement e = etablissementRepository.findById(etablissementId).orElse(null);
        if (e == null) {
            log.warn("Paiement ({}) pour un établissement introuvable : id={}", source, etablissementId);
            return false;
        }
        int m = Math.max(1, mois);

        paiementRepository.save(Paiement.builder()
                .etablissementId(e.getId())
                .montant(montantFcfa)
                .moisCredites(m)
                .reference(reference)
                .enregistrePar(source)
                .build());

        // Un paiement fait passer un établissement Free au plan Pro (illimité), ET prolonge —
        // atomiquement, quelle que soit la source (M6).
        if (e.getPlan() == Plan.FREE) {
            e.setPlan(Plan.PRO);
            e.setMaxEnseignants(0);
        }
        abonnementService.prolonger(e, m);
        etablissementRepository.save(e);

        final Etablissement etab = e;
        userRepository.findFirstByEtablissementIdOrderByIdAsc(e.getId())
                .ifPresent(u -> mailService.notifierPaiementEnLigne(
                        u.getEmail(), etab.getNom(), montantFcfa, etab.getDateExpiration()));

        log.info("Paiement enregistré ({}) : établissement={} montant={} mois={}", source, e.getId(), montantFcfa, m);
        return true;
    }
}
