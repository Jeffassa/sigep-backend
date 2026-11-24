package ci.esatic.sigep.annuaire;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Les huit pays de l'UEMOA : même monnaie (le franc CFA, XOF), donc mêmes tarifs. Chacun a ses
 * propres opérateurs de Mobile Money, d'où leur liste par pays.
 *
 * <p>Seule la Côte d'Ivoire est ouverte : le prestataire de paiement (Jèko) n'encaisse que là.
 * Les autres pays sont gardés pour le jour où un prestataire régional sera branché.
 */
public enum PaysUemoa {

    CI("CIV", "Côte d'Ivoire", "+225", List.of("Orange Money", "MTN MoMo", "Moov Money", "Wave")),
    SN("SEN", "Sénégal", "+221", List.of("Orange Money", "Wave", "Free Money")),
    ML("MLI", "Mali", "+223", List.of("Orange Money", "Moov Money", "Wave")),
    BF("BFA", "Burkina Faso", "+226", List.of("Orange Money", "Moov Money", "Wave")),
    BJ("BEN", "Bénin", "+229", List.of("MTN MoMo", "Moov Money", "Celtiis Cash")),
    TG("TGO", "Togo", "+228", List.of("Mixx by Yas", "Moov Money")),
    NE("NER", "Niger", "+227", List.of("Airtel Money", "Moov Money")),
    GW("GNB", "Guinée-Bissau", "+245", List.of("Orange Money", "MTN MoMo"));

    public static final String DEVISE = "XOF";

    private final String iso3;
    private final String nom;
    private final String indicatif;
    private final List<String> mobileMoney;

    PaysUemoa(String iso3, String nom, String indicatif, List<String> mobileMoney) {
        this.iso3 = iso3;
        this.nom = nom;
        this.indicatif = indicatif;
        this.mobileMoney = mobileMoney;
    }

    public String getCode() { return name(); }
    public String getIso3() { return iso3; }
    public String getNom() { return nom; }
    public String getIndicatif() { return indicatif; }
    public List<String> getMobileMoney() { return mobileMoney; }
    public String getDevise() { return DEVISE; }

    /** Pays où SIGEP vend aujourd'hui : l'annuaire n'y collecte et n'y cherche que là. */
    public boolean isOuvert() { return this == CI; }

    public static List<PaysUemoa> ouverts() {
        return Arrays.stream(values()).filter(PaysUemoa::isOuvert).toList();
    }

    /** Le pays d'un code ISO à deux lettres, sans tenir compte de la casse. */
    public static Optional<PaysUemoa> deCode(String code) {
        if (code == null) return Optional.empty();
        return Arrays.stream(values()).filter(p -> p.name().equalsIgnoreCase(code.trim())).findFirst();
    }
}
