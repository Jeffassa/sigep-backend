package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;
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

    @Value("${app.stripe.currency:xof}")
    private String currency;

    @Value("${app.base-url:https://sigep.store}")
    private String baseUrl;

    /** Le paiement en ligne est-il utilisable ? (activé + clé secrète présente) */
    public boolean isEnabled() {
        return enabled && secretKey != null && !secretKey.isBlank();
    }

    /** Convertit un montant FCFA vers l'unité Stripe (plus petite unité de la devise). */
    long versUniteStripe(long fcfa) {
        return ZERO_DECIMAL.contains(currency.toLowerCase()) ? fcfa : fcfa * 100;
    }

    /**
     * Crée une session Stripe Checkout et renvoie l'URL de paiement (redirection).
     * L'établissement, le nombre de mois et le montant FCFA sont mis en métadonnées :
     * le webhook s'en sert pour enregistrer le paiement et prolonger l'abonnement.
     */
    public String creerSessionCheckout(Etablissement etab, int mois, long montantFcfa, String email)
            throws StripeException {
        Stripe.apiKey = secretKey;
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                // {CHECKOUT_SESSION_ID} est remplacé par Stripe : permet la réconciliation au retour.
                .setSuccessUrl(baseUrl + "/admin/abonnement?paye=1&session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(baseUrl + "/admin/abonnement?annule=1")
                .setCustomerEmail(email)
                .putMetadata("etablissementId", String.valueOf(etab.getId()))
                .putMetadata("mois", String.valueOf(mois))
                .putMetadata("montantFcfa", String.valueOf(montantFcfa))
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(currency)
                                .setUnitAmount(versUniteStripe(montantFcfa))
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("Abonnement SIGEP Pro — " + mois + " mois")
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
            return Optional.of(new PaiementStripe(
                    Long.valueOf(md.get("etablissementId")),
                    Integer.parseInt(md.getOrDefault("mois", "1")),
                    Long.parseLong(md.getOrDefault("montantFcfa", "0")),
                    "Stripe " + s.getId()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Paiement Stripe confirmé, prêt à être comptabilisé. */
    public record PaiementStripe(Long etablissementId, int mois, long montant, String reference) {}
}
