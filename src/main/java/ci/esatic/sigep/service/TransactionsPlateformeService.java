package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Paiement;
import ci.esatic.sigep.entity.PaiementIntent;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.SeveriteEvenement;
import ci.esatic.sigep.entity.StatutIntent;
import ci.esatic.sigep.entity.TypeEvenement;
import ci.esatic.sigep.repository.PaiementIntentRepository;
import ci.esatic.sigep.repository.PaiementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Les transactions vues par la plateforme : les trancher à la main quand le fournisseur n'a pas
 * donné de verdict, et retracer l'historique des abonnements d'un établissement.
 *
 * <p>En temps normal, le fournisseur décide (payée ou non) et SIGEP suit. Ici, c'est le super
 * administrateur qui décide, après avoir regardé chez le fournisseur : chaque décision garde
 * son auteur, sa date et sa raison, et s'inscrit au journal de sécurité.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionsPlateformeService {

    /** Ce qu'on peut confirmer « payée » : tout sauf une transaction déjà créditée ou encore suivie. */
    public static final Set<StatutIntent> CONFIRMABLES =
            EnumSet.of(StatutIntent.A_VERIFIER, StatutIntent.EXPIRE, StatutIntent.ECHOUE);

    /** Ce qu'on peut classer « non payée ». */
    public static final Set<StatutIntent> CLASSABLES_NON_PAYEES =
            EnumSet.of(StatutIntent.A_VERIFIER, StatutIntent.EXPIRE);

    static final int NOTE_MAX = 500;

    private final PaiementIntentRepository intents;
    private final PaiementRepository paiements;
    private final PaiementService paiementService;
    private final JournalSecuriteService journal;

    /** Référence du paiement que crédite une transaction en ligne (même forme que le crédit automatique). */
    public static String referencePaiement(PaiementIntent i) {
        return i.getFournisseur() + " " + i.getReference();
    }

    public Optional<Paiement> paiementCredite(PaiementIntent i) {
        return paiements.findFirstByReference(referencePaiement(i));
    }

    /**
     * Le fournisseur montre que l'argent est arrivé : on crédite l'abonnement, comme l'aurait fait
     * la confirmation automatique, et on garde la trace de la décision.
     */
    @Transactional
    public PaiementIntent confirmerPayee(String reference, Long montantRecu, String note, String auteur) {
        PaiementIntent i = trouver(reference);
        if (!CONFIRMABLES.contains(i.getStatut())) {
            throw new IllegalArgumentException(i.getStatut() == StatutIntent.REUSSI
                    ? "Cette transaction est déjà payée et créditée."
                    : "Cette transaction est encore suivie automatiquement : attendez son verdict.");
        }
        String raison = noteObligatoire(note);
        long montant = montantRecu == null ? i.getMontantAttendu() : montantRecu;
        if (montant <= 0) throw new IllegalArgumentException("Le montant reçu doit être positif.");

        boolean credite = paiementService.enregistrer(i.getEtablissementId(), i.getMois(), montant,
                referencePaiement(i), "Vérifié par " + auteur, i.getPlan(), i.getDevise());
        StatutIntent avant = i.getStatut();
        i.setStatut(StatutIntent.REUSSI);
        i.setMessage(credite ? "Confirmée à la main après vérification chez " + i.getFournisseur()
                             : "Confirmée à la main (paiement déjà comptabilisé)");
        tracer(i, raison, auteur);
        journaliser(i, auteur, avant + " -> payée (" + montant + " " + i.getDevise() + ")", raison);
        return intents.save(i);
    }

    /** Le fournisseur montre que rien n'a été encaissé : la transaction est close, sans crédit. */
    @Transactional
    public PaiementIntent classerNonPayee(String reference, String note, String auteur) {
        PaiementIntent i = trouver(reference);
        if (!CLASSABLES_NON_PAYEES.contains(i.getStatut())) {
            throw new IllegalArgumentException(switch (i.getStatut()) {
                case REUSSI -> "Cette transaction est payée et créditée : elle ne peut plus être classée non payée.";
                case ECHOUE -> "Cette transaction est déjà classée non payée.";
                default -> "Cette transaction est encore suivie automatiquement : attendez son verdict.";
            });
        }
        String raison = noteObligatoire(note);
        StatutIntent avant = i.getStatut();
        i.setStatut(StatutIntent.ECHOUE);
        i.setMessage("Classée non payée à la main après vérification chez " + i.getFournisseur());
        tracer(i, raison, auteur);
        journaliser(i, auteur, avant + " -> non payée", raison);
        return intents.save(i);
    }

    // ── Historique des abonnements ─────────────────────────────────────────────

    /**
     * Une période d'abonnement payée.
     *
     * @param reconstituee période calculée en rejouant les paiements (paiements antérieurs à V40)
     */
    public record Periode(Paiement paiement, Plan plan, LocalDate debut, LocalDate fin,
                          boolean reconstituee, boolean enCours, boolean aVenir) {
        public String getSource() {
            String r = paiement.getReference();
            if (r != null && r.startsWith("Jèko ")) return "En ligne · Jèko";
            if (r != null && r.startsWith("NovaSend ")) return "En ligne · NovaSend";
            if (r != null && r.startsWith("Stripe")) return "En ligne · carte";
            return "Enregistré à la main";
        }

        /** Référence de la transaction en ligne qui a crédité la période, sinon null. */
        public String getReferenceTransaction() {
            String r = paiement.getReference();
            if (r == null) return null;
            if (r.startsWith("Jèko ")) return r.substring("Jèko ".length());
            if (r.startsWith("NovaSend ")) return r.substring("NovaSend ".length());
            return null;
        }

        /** Qui a saisi la période à la main ; null quand le fournisseur l'a confirmée seul. */
        public String getSaisiePar() {
            String par = paiement.getEnregistrePar();
            if (par == null || par.endsWith("(Mobile Money)")) return null;
            return par.startsWith("Vérifié par ") ? par : "par " + par;
        }
    }

    /** Les périodes payées d'un établissement, la plus récente d'abord. */
    @Transactional(readOnly = true)
    public List<Periode> historique(Long etablissementId) {
        LocalDate aujourdhui = LocalDate.now();
        List<Periode> periodes = new ArrayList<>();
        LocalDate finPrecedente = null;
        for (Paiement p : paiements.findByEtablissementIdOrderByDatePaiementAscIdAsc(etablissementId)) {
            boolean reconstituee = p.getPeriodeDebut() == null || p.getPeriodeFin() == null;
            LocalDate debut = p.getPeriodeDebut();
            if (debut == null) {
                LocalDate jour = p.getDatePaiement().toLocalDate();
                debut = finPrecedente != null && finPrecedente.isAfter(jour) ? finPrecedente : jour;
            }
            LocalDate fin = p.getPeriodeFin() != null ? p.getPeriodeFin() : debut.plusMonths(p.getMoisCredites());
            periodes.add(new Periode(p, p.getPlan(), debut, fin, reconstituee,
                    !aujourdhui.isBefore(debut) && aujourdhui.isBefore(fin), debut.isAfter(aujourdhui)));
            finPrecedente = fin;
        }
        Collections.reverse(periodes);
        return periodes;
    }

    private PaiementIntent trouver(String reference) {
        return intents.findByReference(reference)
                .orElseThrow(() -> new IllegalArgumentException("Transaction introuvable : " + reference));
    }

    private static String noteObligatoire(String note) {
        String n = note == null ? "" : note.trim();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Indiquez ce que vous avez constaté chez le fournisseur.");
        }
        return n.length() > NOTE_MAX ? n.substring(0, NOTE_MAX) : n;
    }

    private static void tracer(PaiementIntent i, String note, String auteur) {
        i.setTraitePar(auteur);
        i.setDateTraitement(LocalDateTime.now());
        i.setNoteTraitement(note);
    }

    private void journaliser(PaiementIntent i, String auteur, String decision, String note) {
        String details = "Transaction " + i.getReference() + " : " + decision + ". " + note;
        journal.enregistrer(TypeEvenement.REMEDIATION, SeveriteEvenement.INFO, null, auteur,
                "/plateforme/transactions", details.length() > 500 ? details.substring(0, 500) : details,
                i.getEtablissementId());
        log.info("Transaction {} tranchée à la main par {} : {}", i.getReference(), auteur, decision);
    }
}
