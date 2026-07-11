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
 * Enregistre un paiement Stripe réussi et prolonge l'abonnement. Appelé par le webhook.
 * IDEMPOTENT : Stripe peut livrer un événement plusieurs fois — la référence (id de session)
 * garantit qu'un même paiement n'est comptabilisé qu'une seule fois.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StripePaymentService {

    private final EtablissementRepository etablissementRepository;
    private final PaiementRepository paiementRepository;
    private final UserRepository userRepository;
    private final AbonnementService abonnementService;
    private final MailService mailService;

    @Transactional
    public void traiterPaiementReussi(Long etablissementId, int mois, long montantFcfa, String reference) {
        if (reference != null && paiementRepository.existsByReference(reference)) {
            return; // déjà traité (livraison multiple du webhook)
        }
        Etablissement e = etablissementRepository.findById(etablissementId).orElse(null);
        if (e == null) {
            log.warn("Paiement Stripe pour un établissement introuvable : id={}", etablissementId);
            return;
        }
        int m = Math.max(1, mois);

        paiementRepository.save(Paiement.builder()
                .etablissementId(e.getId())
                .montant(montantFcfa)
                .moisCredites(m)
                .reference(reference)
                .enregistrePar("Stripe (en ligne)")
                .build());

        // Un paiement en ligne fait passer un établissement Free au plan Pro.
        if (e.getPlan() == Plan.FREE) {
            e.setPlan(Plan.PRO);
            e.setMaxEnseignants(0); // illimité
        }
        abonnementService.prolonger(e, m);
        etablissementRepository.save(e);

        // Reçu par e-mail à l'administrateur de l'établissement.
        final Etablissement etab = e;
        userRepository.findFirstByEtablissementIdOrderByIdAsc(e.getId())
                .ifPresent(u -> mailService.notifierPaiementEnLigne(
                        u.getEmail(), etab.getNom(), montantFcfa, etab.getDateExpiration()));

        log.info("Paiement Stripe enregistré : établissement={} montant={} mois={}", e.getId(), montantFcfa, m);
    }
}
