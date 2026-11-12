package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.PaiementIntent;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.StatutIntent;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Encaissement Mobile Money automatisé : par Jèko (page de paiement hébergée) ou par NovaSend
 * (demande poussée sur le téléphone). Jèko l'emporte quand les deux sont configurés. Chaque
 * paiement reste attaché à son fournisseur par sa référence (préfixe {@code JK-} pour Jèko).
 *
 * <p><b>Trois principes, chacun issu d'un mode de défaillance réel.</b></p>
 * <ol>
 *   <li><b>Aucun appel réseau dans une transaction.</b> Les écritures passent par
 *       {@link PaiementIntentStore} (transactions courtes) ; les appels NovaSend se font
 *       en dehors. Une connexion JDBC retenue le temps d'un aller-retour HTTP suffirait,
 *       sous sondage régulier, à vider le pool et à mettre tout le SaaS à l'arrêt.</li>
 *   <li><b>On ne clôt jamais un paiement sans verdict du fournisseur.</b> « NovaSend
 *       injoignable » n'est pas « paiement inexistant » : traiter une panne réseau comme un
 *       échec ferait perdre des paiements réellement encaissés, sans rattrapage possible.
 *       Au-delà de la fenêtre de suivi, l'intention part en {@link StatutIntent#A_VERIFIER}
 *       (arbitrage humain), jamais en échec silencieux.</li>
 *   <li><b>Le crédit reste idempotent.</b> Webhook, retour navigateur et relanceur convergent
 *       vers la même transition, qui relit l'intention et exige qu'elle soit encore en cours.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@org.springframework.context.annotation.Lazy(false)   // eager : le relanceur @Scheduled doit tourner malgré lazy-init
public class MobileMoneyService {

    /** Plafond de mois par paiement (aligné sur PaiementService). */
    private static final int MAX_MOIS = 24;

    private final NovaSendService novaSend;
    private final JekoService jeko;
    private final PaiementIntentStore store;
    private final PlanService planService;

    @Value("${app.base-url:https://sigep.store}")
    private String baseUrl;

    /** Durée pendant laquelle on fait patienter l'utilisateur devant la page d'attente. */
    @Value("${app.novasend.expiration-minutes:30}")
    private int expirationMinutes;

    /** Durée pendant laquelle on continue à réclamer un verdict en arrière-plan. */
    @Value("${app.novasend.suivi-heures:48}")
    private int suiviHeures;

    /** Nombre de jours de conservation des intentions terminées (RGPD : msisdn du payeur). */
    @Value("${app.novasend.retention-jours:90}")
    private int retentionJours;

    public boolean isEnabled() {
        return jeko.isEnabled() || novaSend.isEnabled();
    }

    /** Fournisseur proposé aux établissements : JEKO, NOVASEND, ou null si aucun n'est configuré. */
    public String passerelle() {
        if (jeko.isEnabled()) return "JEKO";
        if (novaSend.isEnabled()) return "NOVASEND";
        return null;
    }

    /** Mode test déclaré : argent fictif côté fournisseur, mais abonnements bien réels ici.
     *  Jèko n'a pas de bac à sable : avec lui, tout paiement est réel. */
    public boolean estSandbox() {
        return !jeko.isEnabled() && novaSend.estSandbox();
    }

    /**
     * Prépare un paiement Jèko et rend l'intention, qui porte l'adresse de la page de paiement.
     * Comme pour NovaSend, le montant est recalculé ici et l'intention est enregistrée avant tout
     * appel à Jèko : il ne doit jamais exister de paiement sans trace côté SIGEP.
     */
    public PaiementIntent initierJeko(Etablissement etab, Plan plan, int mois, String moyen) {
        if (!jeko.isEnabled()) {
            throw new JekoService.JekoException("Le paiement par Jèko n'est pas activé.");
        }
        if (!planService.estAchetable(plan)) {
            throw new JekoService.JekoException("Ce plan n'est pas disponible à l'achat.");
        }
        if (!jeko.moyenSupporte(moyen)) {
            throw new JekoService.JekoException("Choisissez Wave, Orange Money, MTN MoMo, Moov Money ou Djamo.");
        }

        // Une demande déjà en cours est reprise plutôt que doublée : un second paiement serait un
        // second débit. Si elle a déjà une page de paiement, l'administrateur y retourne.
        var dejaEnVol = store.enCoursPourEtablissement(etab.getId());
        if (dejaEnVol.isPresent()) {
            log.info("Jèko : une demande est déjà en cours (ref={}), elle est reprise", dejaEnVol.get().getReference());
            return dejaEnVol.get();
        }

        int m = Math.min(MAX_MOIS, Math.max(1, mois));
        long montant = (long) m * planService.prixMensuel(plan);
        String devise = planService.devise().toUpperCase();
        String reference = JekoService.PREFIXE_REFERENCE + UUID.randomUUID();

        PaiementIntent intent = store.creer(PaiementIntent.builder()
                .reference(reference)
                .etablissementId(etab.getId())
                .plan(plan)
                .mois(m)
                .montantAttendu(montant)
                .devise(devise)
                .provider(moyen.trim().toUpperCase())
                .statut(StatutIntent.EN_COURS)
                .build());

        String retour = baseUrl + "/admin/abonnement/momo/retour?ref=" + reference;
        try {
            JekoService.Demande d = jeko.creerDemande(reference, montant, devise, moyen, retour);
            store.majApresInitiation(reference, d.id(), d.redirectUrl(), d.statut());
            intent.setNovasendId(d.id());
            intent.setPaymentUrl(d.redirectUrl());
        } catch (RuntimeException e) {
            // Refus net de Jèko : rien n'a été créé chez lui, la demande est close. Sinon (réseau),
            // on ne sait pas : un humain vérifiera dans le tableau de bord Jèko.
            boolean refusNet = e instanceof JekoService.JekoException
                    && !e.getMessage().contains("momentanément indisponible");
            store.marquer(reference, refusNet ? StatutIntent.ECHOUE : StatutIntent.A_VERIFIER,
                    (refusNet ? "Refusé par Jèko : " : "Création incertaine : ") + e.getMessage());
            throw e;
        }
        log.info("Jèko : paiement préparé ref={} etab={} plan={} mois={} montant={}",
                reference, etab.getId(), plan, m, montant);
        return intent;
    }

    /**
     * Initie un paiement Mobile Money. Le montant est TOUJOURS recalculé ici : rien de ce que
     * le navigateur envoie ne peut l'influencer.
     *
     * <p>L'intention est committée AVANT l'appel au fournisseur : la demande de paiement est un
     * effet de bord irréversible, il ne doit jamais exister de débit sans trace côté SIGEP.
     */
    public PaiementIntent initier(Etablissement etab, Plan plan, int mois, String msisdn,
                                  String provider, String otp, String customerName) {
        if (!novaSend.isEnabled()) {
            throw new NovaSendService.NovaSendException("Le paiement Mobile Money n'est pas activé.");
        }
        if (!planService.estAchetable(plan)) {
            throw new NovaSendService.NovaSendException("Ce plan n'est pas disponible à l'achat.");
        }
        if (!novaSend.providerSupporte(provider)) {
            throw new NovaSendService.NovaSendException("Opérateur non supporté.");
        }
        String numero = normaliserMsisdn(msisdn);
        if (numero == null) {
            throw new NovaSendService.NovaSendException(
                    "Numéro invalide. Indiquez-le au format international, ex. +2250700000000.");
        }
        if (novaSend.otpRequis(provider)) {
            if (otp == null || !otp.trim().matches("[0-9]{4,8}")) {
                throw new NovaSendService.NovaSendException(
                        "Orange Money exige un code OTP à 4-8 chiffres : composez #144*82# pour l'obtenir.");
            }
        }

        // Anti double-clic : une demande déjà en vol serait un SECOND débit du client.
        var dejaEnVol = store.enCoursPourEtablissement(etab.getId());
        if (dejaEnVol.isPresent()) {
            log.info("Mobile Money : initiation ignorée, une demande est déjà en cours (ref={})",
                    dejaEnVol.get().getReference());
            return dejaEnVol.get();
        }

        int m = Math.min(MAX_MOIS, Math.max(1, mois));
        long montant = (long) m * planService.prixMensuel(plan);
        String reference = UUID.randomUUID().toString();

        // 1) Trace committée AVANT tout effet de bord externe.
        PaiementIntent intent = store.creer(PaiementIntent.builder()
                .reference(reference)
                .etablissementId(etab.getId())
                .plan(plan)
                .mois(m)
                .montantAttendu(montant)
                .devise(planService.devise().toUpperCase())
                .msisdn(numero)
                .provider(provider.toUpperCase())
                .statut(StatutIntent.EN_COURS)
                .build());

        // 2) Effet de bord externe, hors transaction.
        String retour = baseUrl + "/admin/abonnement/momo/retour?ref=" + reference;
        try {
            NovaSendService.Reponse rep = novaSend.initierPayin(
                    reference, montant, numero, provider, customerName, otp, retour, retour);
            if (rep != null) {
                store.majApresInitiation(reference, rep.id(), rep.paymentUrl(), rep.status());
                intent.setNovasendId(rep.id());
                intent.setPaymentUrl(rep.paymentUrl());
            }
        } catch (RuntimeException e) {
            // L'intention reste en base : si la demande est malgré tout partie, le relanceur la
            // rattrapera. On la marque pour ne pas laisser l'utilisateur devant une page muette.
            store.marquer(reference, StatutIntent.A_VERIFIER,
                    "Initiation incertaine : " + e.getMessage());
            throw e;
        }
        log.info("Mobile Money initié : ref={} etab={} plan={} mois={} montant={}",
                reference, etab.getId(), plan, m, montant);
        return intent;
    }

    /**
     * Interroge NovaSend (HORS transaction) et applique le verdict.
     * Ne crédite que si le paiement est réellement encaissé, dans la bonne devise et pour au
     * moins le montant attendu figé à l'initiation.
     */
    public StatutIntent verifierEtCrediter(String reference) {
        PaiementIntent intent = store.parReference(reference).orElse(null);
        if (intent == null) return null;
        if (intent.getStatut() != StatutIntent.EN_COURS) {
            return intent.getStatut();   // déjà tranchée : idempotent
        }
        if (reference.startsWith(JekoService.PREFIXE_REFERENCE)) {
            return verifierJeko(intent);
        }

        NovaSendService.Statut statut = novaSend.statut(reference);   // appel réseau, hors transaction

        if (!statut.aUnVerdict()) {
            // Pas de verdict : soit le fournisseur est injoignable, soit il ne connaît pas la
            // référence. Dans les deux cas on ne clôt PAS en échec — on continue à réclamer.
            return sansVerdict(intent, statut.etat());
        }

        NovaSendService.Reponse rep = statut.reponse();

        if (rep.estPaye()) {
            boolean deviseOk = rep.currency() != null
                    && rep.currency().equalsIgnoreCase(intent.getDevise());
            boolean montantOk = rep.amount() >= intent.getMontantAttendu();
            if (!deviseOk || !montantOk) {
                // Argent probablement encaissé mais non conforme : cas d'ARBITRAGE, surtout pas
                // un « échec » — on ne peut pas affirmer au client qu'il n'a pas été débité.
                log.error("Mobile Money À VÉRIFIER (écart) ref={} : payé {} {} / attendu {} {}",
                        reference, rep.amount(), rep.currency(),
                        intent.getMontantAttendu(), intent.getDevise());
                return store.marquer(reference, StatutIntent.A_VERIFIER,
                        "Montant ou devise divergents : vérification manuelle requise.");
            }
            return store.crediter(reference, rep.amount());
        }

        if (rep.estEchoue()) {
            return store.marquer(reference, StatutIntent.ECHOUE, "Paiement refusé ou annulé");
        }

        // Encore en traitement chez l'opérateur (le client est dans son menu USSD).
        return sansVerdict(intent, NovaSendService.Etat.VERDICT);
    }

    /**
     * Paiement Jèko : l'état est lu chez Jèko avec l'identifiant de la demande. Le montant payé
     * est fixé par SIGEP à la création, mais il est quand même contrôlé : Jèko l'exprime en
     * centimes ; une valeur en francs est aussi reconnue. Tout autre montant part en arbitrage.
     */
    private StatutIntent verifierJeko(PaiementIntent intent) {
        String reference = intent.getReference();
        JekoService.Lecture lecture = jeko.lire(intent.getNovasendId());   // appel réseau, hors transaction
        if (!lecture.aUnVerdict()) {
            return sansVerdict(intent, lecture.etat() == JekoService.Etat.INTROUVABLE
                    ? NovaSendService.Etat.INTROUVABLE : NovaSendService.Etat.INJOIGNABLE);
        }
        JekoService.Demande d = lecture.demande();
        if (d.estPayee()) {
            long attendu = intent.getMontantAttendu();
            boolean deviseOk = d.devise() == null || d.devise().isBlank()
                    || d.devise().equalsIgnoreCase(intent.getDevise());
            boolean montantOk = d.montant() == attendu * 100 || d.montant() == attendu;
            if (!deviseOk || !montantOk) {
                log.error("Jèko À VÉRIFIER (écart) ref={} : payé {} {} / attendu {} {}",
                        reference, d.montant(), d.devise(), attendu, intent.getDevise());
                return store.marquer(reference, StatutIntent.A_VERIFIER,
                        "Montant ou devise divergents : vérification manuelle requise.");
            }
            return store.crediter(reference, attendu);
        }
        if (d.estEchouee()) {
            return store.marquer(reference, StatutIntent.ECHOUE,
                    "Paiement non abouti" + (d.raisonEchec() == null ? "" : " (" + d.raisonEchec() + ")"));
        }
        return sansVerdict(intent, NovaSendService.Etat.VERDICT);   // en attente chez Jèko
    }

    /**
     * Aucun verdict exploitable : on laisse l'intention en cours tant qu'on est dans la fenêtre
     * de suivi, puis on la confie à un humain. Jamais d'échec inventé sur un silence.
     */
    private StatutIntent sansVerdict(PaiementIntent intent, NovaSendService.Etat etat) {
        LocalDateTime creation = intent.getDateCreation();
        if (creation != null && creation.isBefore(LocalDateTime.now().minusHours(suiviHeures))) {
            log.warn("Mobile Money : aucun verdict après {} h (ref={}, dernier état={}) — arbitrage requis",
                    suiviHeures, intent.getReference(), etat);
            return store.marquer(intent.getReference(), StatutIntent.A_VERIFIER,
                    "Aucune confirmation obtenue du fournisseur : vérification manuelle requise.");
        }
        return StatutIntent.EN_COURS;
    }

    /** L'utilisateur doit-il cesser d'attendre devant sa page ? (le suivi, lui, continue) */
    public boolean attenteDepassee(PaiementIntent intent) {
        return intent != null && intent.getDateCreation() != null
                && intent.getDateCreation().isBefore(LocalDateTime.now().minusMinutes(expirationMinutes));
    }

    /**
     * Relanceur : rattrape les paiements confirmés dont le client n'est jamais revenu (navigateur
     * fermé) et ceux dont le webhook n'est pas arrivé. Sans lui, un abonnement payé pourrait
     * n'être jamais crédité.
     */
    @Scheduled(fixedDelayString = "${app.novasend.poll-ms:60000}", initialDelayString = "${app.novasend.poll-ms:60000}")
    @SchedulerLock(name = "novasendRelance", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void relancerIntentionsEnCours() {
        // Pas de garde sur l'activation : un paiement lancé avant qu'on désactive le fournisseur
        // doit quand même recevoir son verdict. Sans paiement en cours, ce tour ne coûte qu'une lecture.
        List<PaiementIntent> enCours = store.enCours();
        // Borne de sécurité : le balayage ne doit jamais devenir un travail illimité.
        int traitees = 0;
        for (PaiementIntent intent : enCours) {
            if (traitees++ >= 200) {
                log.warn("Mobile Money : {} intentions en cours, balayage tronqué à 200 ce tour",
                        enCours.size());
                break;
            }
            try {
                verifierEtCrediter(intent.getReference());
            } catch (Exception e) {
                log.warn("Relance Mobile Money échouée (ref={}) : {}", intent.getReference(), e.getMessage());
            }
        }
    }

    /** Purge RGPD quotidienne : le numéro du payeur n'a pas à être conservé indéfiniment. */
    @Scheduled(cron = "${app.novasend.purge-cron:0 15 4 * * *}")
    @SchedulerLock(name = "novasendPurge", lockAtMostFor = "PT10M")
    public void purgerAnciennesIntentions() {
        int n = store.purger(LocalDateTime.now().minus(Duration.ofDays(retentionJours)));
        if (n > 0) log.info("Mobile Money : {} intentions terminées purgées (> {} j)", n, retentionJours);
    }

    /**
     * Normalise et valide le numéro au format international attendu par le fournisseur.
     * Accepte les saisies usuelles (espaces, points, 00 en préfixe) mais n'émet qu'un format
     * canonique : une saisie locale sans indicatif serait rejetée par l'opérateur.
     */
    String normaliserMsisdn(String saisie) {
        if (saisie == null) return null;
        String s = saisie.trim().replaceAll("[\\s.\\-()]", "");
        if (s.startsWith("00")) s = "+" + s.substring(2);
        if (!s.startsWith("+")) return null;              // indicatif pays obligatoire
        return s.matches("\\+[1-9][0-9]{7,14}") ? s : null;
    }
}
