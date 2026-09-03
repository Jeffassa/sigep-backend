package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Intégration Stripe (paiement en ligne par carte) — Checkout hébergé.
 * Désactivé par défaut : ne s'active qu'avec STRIPE_ENABLED=true + STRIPE_SECRET_KEY.
 * Le webhook (StripeWebhookController) est la source de vérité qui enregistre le paiement ;
 * la page de succès n'est qu'informative.
 *
 * NB : Stripe ne gère pas le Mobile Money en Côte d'Ivoire — utilisation en mode test
 * (cartes de test) pour valider le flux. Devise configurable (défaut XOF, sans décimales).
 */
@Service
public class StripeService {

    /** Devises « zéro décimale » (montant Stripe = montant réel, sans ×100). */
    private static final Set<String> ZERO_DECIMAL = Set.of(
            "xof", "xaf", "bif", "clp", "djf", "gnf", "jpy", "kmf", "krw",
            "mga", "pyg", "rwf", "ugx", "vnd", "vuv", "xpf");

    @Value("${app.stripe.enabled:false}")
    private boolean enabled;

    @Value("${app.stripe.secret-key:}")
    private String secretKey;

    /** Devise SOURCE UNIQUE (app.billing.currency) : impossible d'encaisser dans une devise
     *  différente de celle affichée au client. */
    @Value("${app.billing.currency:eur}")
    private String currency;

    @Value("${app.base-url:https://sigep.store}")
    private String baseUrl;

    /** Le paiement en ligne est-il utilisable ? (activé + clé secrète présente) */
    public boolean isEnabled() {
        return enabled && secretKey != null && !secretKey.isBlank();
    }

    /**
     * Convertit un montant exprimé en unité MAJEURE (ex. 20 €) vers l'unité Stripe
     * (plus petite unité : 2000 centimes en EUR, 20 en XOF qui est sans décimale).
     */
    public long versUniteStripe(long montant) {
        return ZERO_DECIMAL.contains(currency.toLowerCase()) ? montant : montant * 100;
    }

    /**
     * Crée une session Stripe Checkout et renvoie l'URL de paiement (redirection).
     * L'établissement, le PLAN acheté, le nombre de mois, le montant et la devise sont mis en
     * métadonnées : le webhook et la réconciliation au retour s'en servent pour enregistrer le
     * paiement, appliquer le bon plan et prolonger l'abonnement.
     */
    public String creerSessionCheckout(Etablissement etab, Plan plan, int mois, long montant, String email)
            throws StripeException {
        Stripe.apiKey = secretKey;
        String libelle = plan == Plan.ENTERPRISE ? "Enterprise" : "Pro";
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                // {CHECKOUT_SESSION_ID} est remplacé par Stripe : permet la réconciliation au retour.
                .setSuccessUrl(baseUrl + "/admin/abonnement?paye=1&session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(baseUrl + "/admin/abonnement?annule=1")
                .setCustomerEmail(email)
                .putMetadata("etablissementId", String.valueOf(etab.getId()))
                .putMetadata("mois", String.valueOf(mois))
                .putMetadata("montant", String.valueOf(montant))
                // Alias conservé pour compatibilité avec les livraisons de webhook en vol.
                .putMetadata("montantFcfa", String.valueOf(montant))
                .putMetadata("plan", plan == null ? Plan.PRO.name() : plan.name())
                .putMetadata("devise", currency.toUpperCase())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(currency)
                                .setUnitAmount(versUniteStripe(montant))
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("Abonnement SIGEP " + libelle + " — " + mois + " mois")
                                        .build())
                                .build())
                        .build())
                .build();
        return Session.create(params).getUrl();
    }

    /**
     * Réconciliation au retour de paiement (source de secours du webhook) : récupère la session
     * via l'API Stripe et, si elle est PAYÉE, renvoie les infos à comptabiliser. N'utilise que la
     * clé secrète (déjà configurée) — indépendant du secret du webhook.
     */
    public Optional<PaiementStripe> recupererSessionPayee(String sessionId) throws StripeException {
        Stripe.apiKey = secretKey;
        Session s = Session.retrieve(sessionId);
        if (!"paid".equals(s.getPaymentStatus())) {
            return Optional.empty();
        }
        Map<String, String> md = s.getMetadata();
        if (md == null || md.get("etablissementId") == null) {
            return Optional.empty();
        }
        try {
            long montant = Long.parseLong(
                    md.getOrDefault("montant", md.getOrDefault("montantFcfa", "0")));
            // SECURITE : le montant réellement encaissé doit correspondre au montant attendu
            // (même contrôle anti-écart que le webhook). Sinon on NE crédite PAS.
            Long encaisse = s.getAmountTotal();
            if (encaisse == null || encaisse.longValue() != versUniteStripe(montant)) {
                return Optional.empty();
            }
            Plan plan = null;
            try {
                plan = Plan.valueOf(md.getOrDefault("plan", ""));
            } catch (IllegalArgumentException ignore) { /* métadonnée absente/ancienne */ }
            return Optional.of(new PaiementStripe(
                    Long.valueOf(md.get("etablissementId")),
                    Integer.parseInt(md.getOrDefault("mois", "1")),
                    montant,
                    "Stripe " + s.getId(),
                    plan,
                    md.get("devise")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Paiement Stripe confirmé, prêt à être comptabilisé. */
    public record PaiementStripe(Long etablissementId, int mois, long montant, String reference,
                                 Plan plan, String devise) {}
}
