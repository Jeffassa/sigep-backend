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

    /** Plafond de mois crédités par paiement (défense : pas de prolongation arbitraire si un secret fuit). */
    private static final int MAX_MOIS_PAR_PAIEMENT = 24;

    private final EtablissementRepository etablissementRepository;
    private final PaiementRepository paiementRepository;
    private final UserRepository userRepository;
    private final AbonnementService abonnementService;
    private final MailService mailService;
    private final ci.esatic.sigep.tenant.plan.PlanService planService;

    /**
     * Variante historique (sans plan ni devise explicites) : conserve le comportement
     * « un paiement fait passer Free → Pro », et applique la devise de facturation courante.
     */
    @Transactional
    public boolean enregistrer(Long etablissementId, int mois, long montant, String reference, String source) {
        return enregistrer(etablissementId, mois, montant, reference, source, null, null);
    }

    /**
     * Enregistre un paiement et met à jour l'abonnement en une transaction.
     * IDEMPOTENT : si {@code reference} a déjà été traitée, ne fait rien (livraisons multiples).
     *
     * @param planAchete plan réellement payé (PRO/ENTERPRISE) ; {@code null} = comportement
     *                   historique (Free → Pro, sinon on conserve le plan courant).
     * @param devise     devise du paiement (ISO) ; {@code null} = devise de facturation courante.
     * @return true si le paiement a été enregistré, false s'il était déjà traité / tenant introuvable.
     */
    @Transactional
    public boolean enregistrer(Long etablissementId, int mois, long montant, String reference,
                               String source, Plan planAchete, String devise) {
        if (reference != null && paiementRepository.existsByReference(reference)) {
            return false; // déjà traité
        }
        Etablissement e = etablissementRepository.findById(etablissementId).orElse(null);
        if (e == null) {
            log.warn("Paiement ({}) pour un établissement introuvable : id={}", source, etablissementId);
            return false;
        }
        int m = Math.min(MAX_MOIS_PAR_PAIEMENT, Math.max(1, mois));

        // Plan à créditer : celui explicitement payé, sinon comportement historique (Free → Pro).
        Plan cible = planAchete != null ? planAchete
                : (e.getPlan() == Plan.FREE ? Plan.PRO : e.getPlan());
        String dev = (devise != null && !devise.isBlank())
                ? devise.toUpperCase() : planService.devise().toUpperCase();

        // saveAndFlush : la contrainte UNIQUE(reference) rend l'enregistrement idempotent même en
        // course (deux livraisons simultanées) — la requête perdante échoue ici, sa transaction est
        // annulée (aucun double crédit) et le webhook rejoue (la voie rapide existsByReference l'ignore).
        paiementRepository.saveAndFlush(Paiement.builder()
                .etablissementId(e.getId())
                .montant(montant)
                .moisCredites(m)
                .reference(reference)
                .enregistrePar(source)
                .devise(dev)
                .plan(cible)
                .build());

        // Le paiement applique le plan acheté (Pro ou Enterprise) et lève le quota, ET prolonge —
        // atomiquement, quelle que soit la source (M6).
        if (cible != null && cible != Plan.FREE) {
            e.setPlan(cible);
            e.setMaxEnseignants(0);   // plans payants : enseignants illimités
        }
        // F6 : un paiement réactive un établissement suspendu (ex. pour impayé).
        if (!e.isActif()) {
            e.setActif(true);
            log.info("Établissement {} réactivé suite à paiement", e.getId());
        }
        abonnementService.prolonger(e, m);
        etablissementRepository.save(e);

        final Etablissement etab = e;
        userRepository.findFirstByEtablissementIdOrderByIdAsc(e.getId())
                .ifPresent(u -> mailService.notifierPaiementEnLigne(
                        u.getEmail(), etab.getNom(), montant, etab.getDateExpiration()));

        log.info("Paiement enregistré ({}) : établissement={} montant={} {} plan={} mois={}",
                source, e.getId(), montant, dev, cible, m);
        return true;
    }
}
