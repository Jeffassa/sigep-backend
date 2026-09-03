package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.PaiementIntent;
import ci.esatic.sigep.entity.StatutIntent;
import ci.esatic.sigep.repository.PaiementIntentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Accès transactionnel aux intentions de paiement, isolé dans son propre bean.
 *
 * <p>Deux raisons d'être :
 * <ol>
 *   <li><b>Transactions courtes.</b> Les appels réseau vers NovaSend restent HORS transaction :
 *       une connexion JDBC retenue pendant un aller-retour HTTP épuiserait le pool et gèlerait
 *       l'application entière. Ici, chaque méthode ouvre et referme une transaction brève.</li>
 *   <li><b>Pas d'auto-invocation.</b> Appelée depuis un autre bean, la sémantique
 *       {@code @Transactional} s'applique réellement (un appel interne l'aurait contournée,
 *       laissant des écritures hors transaction).</li>
 * </ol>
 *
 * <p>Chaque transition relit l'intention DANS la transaction et vérifie qu'elle est encore
 * {@code EN_COURS} : deux chemins concurrents (webhook, retour navigateur, relanceur) ne
 * peuvent donc pas appliquer deux fois le même verdict.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaiementIntentStore {

    private final PaiementIntentRepository repository;
    private final PaiementService paiementService;

    /**
     * Persiste l'intention dans sa PROPRE transaction, committée immédiatement.
     * Indispensable : la demande de paiement partant ensuite chez l'opérateur est un effet de
     * bord irréversible ; si l'intention n'était pas déjà committée, un échec ultérieur
     * l'effacerait et le paiement du client deviendrait intraçable (donc jamais crédité).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaiementIntent creer(PaiementIntent intent) {
        return repository.save(intent);
    }

    /** Complète l'intention avec les identifiants renvoyés par le fournisseur. */
    @Transactional
    public void majApresInitiation(String reference, String novasendId, String paymentUrl, String message) {
        repository.findByReference(reference).ifPresent(i -> {
            i.setNovasendId(novasendId);
            i.setPaymentUrl(paymentUrl);
            i.setMessage(message);
            repository.save(i);
        });
    }

    /**
     * Crédite l'abonnement et marque l'intention REUSSI, atomiquement.
     * Relit l'intention dans la transaction : si un autre chemin l'a déjà traitée, on ne fait rien.
     *
     * @return le statut final de l'intention.
     */
    @Transactional
    public StatutIntent crediter(String reference, long montantRecu) {
        PaiementIntent i = repository.findByReference(reference).orElse(null);
        if (i == null) return null;
        if (i.getStatut() != StatutIntent.EN_COURS) return i.getStatut();   // déjà traitée

        boolean enregistre = paiementService.enregistrer(i.getEtablissementId(), i.getMois(),
                montantRecu, "NovaSend " + reference, "NovaSend (Mobile Money)",
                i.getPlan(), i.getDevise());

        i.setStatut(StatutIntent.REUSSI);
        // On distingue le crédit effectif d'un doublon déjà comptabilisé : sans cela, un log
        // « crédité » mentirait sur ce qui s'est réellement passé.
        i.setMessage(enregistre ? "Paiement confirmé et crédité"
                                : "Paiement confirmé (déjà comptabilisé)");
        repository.save(i);
        log.info("Mobile Money : intention {} -> REUSSI (crédit effectif={})", reference, enregistre);
        return StatutIntent.REUSSI;
    }

    /** Applique un statut terminal (ou d'arbitrage) si l'intention est encore en cours. */
    @Transactional
    public StatutIntent marquer(String reference, StatutIntent statut, String message) {
        PaiementIntent i = repository.findByReference(reference).orElse(null);
        if (i == null) return null;
        if (i.getStatut() != StatutIntent.EN_COURS) return i.getStatut();
        i.setStatut(statut);
        i.setMessage(message);
        repository.save(i);
        return statut;
    }

    @Transactional(readOnly = true)
    public Optional<PaiementIntent> parReference(String reference) {
        return repository.findByReference(reference);
    }

    @Transactional(readOnly = true)
    public List<PaiementIntent> enCours() {
        return repository.findByStatutOrderByDateCreationAsc(StatutIntent.EN_COURS);
    }

    /** Une initiation est-elle déjà en vol pour cet établissement ? (anti double-clic) */
    @Transactional(readOnly = true)
    public Optional<PaiementIntent> enCoursPourEtablissement(Long etablissementId) {
        return repository.findByEtablissementIdOrderByDateCreationDesc(etablissementId).stream()
                .filter(i -> i.getStatut() == StatutIntent.EN_COURS)
                .findFirst();
    }

    /**
     * Purge RGPD : les intentions terminées conservent le numéro de téléphone du payeur,
     * donnée personnelle qui n'a pas à être gardée indéfiniment.
     */
    @Transactional
    public int purger(LocalDateTime avant) {
        List<PaiementIntent> vieilles =
                repository.findByStatutNotAndDateCreationBefore(StatutIntent.EN_COURS, avant);
        repository.deleteAll(vieilles);
        return vieilles.size();
    }
}
