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

    private final PaiementService paiementService;

    /** Délègue au chemin unifié et idempotent (M6). Conserve la signature appelée par le webhook. */
    @Transactional
    public void traiterPaiementReussi(Long etablissementId, int mois, long montantFcfa, String reference) {
        paiementService.enregistrer(etablissementId, mois, montantFcfa, reference, "Stripe (en ligne)");
    }
}
