package ci.esatic.sigep.entity;

import ci.esatic.sigep.tenant.TenantListener;
import ci.esatic.sigep.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Le fournisseur d'identité d'un établissement — sa façon à lui de dire qui entre.
 *
 * <p>Un établissement qui achète l'offre Enterprise a déjà un annuaire : Google Workspace,
 * Microsoft Entra, Keycloak. Le SSO consiste à l'écouter plutôt qu'à tenir un second jeu de mots
 * de passe — ce qui veut surtout dire qu'un départ d'agent, révoqué chez eux, l'est chez nous le
 * jour même, sans que personne ait à y penser.
 *
 * <p><b>Nous sommes client, jamais fournisseur.</b> Nous consommons une identité ; nous n'en
 * émettons aucune, et nous ne stockons aucun mot de passe du fournisseur.
 *
 * <h2>Pourquoi tenant-scoped malgré une lecture AVANT connexion</h2>
 *
 * <p>Le filtre Hibernate n'est posé que pour une requête authentifiée. La lecture faite au moment
 * d'ouvrir une session SSO survient, elle, sans contexte : le filtre ne s'applique pas, et c'est
 * voulu — à cet instant on ne sait pas encore qui frappe à la porte. Elle passe donc par
 * {@code findByEtablissementId}, qui ne peut pas rendre la ligne d'un autre. Une fois connecté,
 * l'écran d'administration retombe sous le filtre comme n'importe quelle autre donnée.
 */
@Entity
@Table(name = "configurations_sso")
@Filter(name = "tenantFilter", condition = "etablissement_id = :tenantId")
@EntityListeners(TenantListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfigurationSso implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "etablissement_id", nullable = false, unique = true)
    private Long etablissementId;

    /** Nom montré sur le bouton de connexion. Cosmétique : l'autorité, c'est {@link #issuer}. */
    @Column(nullable = false, length = 80)
    private String fournisseur;

    /**
     * URL de l'émetteur OIDC, d'où tout le reste est découvert.
     *
     * <p>Point d'autorisation, point de jeton, clés de signature : tout vient de
     * {@code issuer + /.well-known/openid-configuration}. Rien n'est recopié à la main, donc
     * rien ne se désaligne — et une rotation de clés chez le fournisseur ne nous demande rien.
     */
    @Column(nullable = false, length = 400)
    private String issuer;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    /**
     * Secret client, CHIFFRÉ.
     *
     * <p>Chiffré et non haché : il faut le relire en clair pour appeler le point d'accès « token »
     * du fournisseur. Une sauvegarde de base qui fuite ne livre donc rien d'utilisable sans la
     * clé, qui vit en variable d'environnement et n'est jamais écrite ici.
     */
    @lombok.ToString.Exclude
    @Column(name = "client_secret", nullable = false, columnDefinition = "TEXT")
    private String clientSecret;

    /** Domaines de courriel acceptés, séparés par des virgules. Vide = pas de restriction. */
    @Column(length = 500)
    private String domaines;

    @Builder.Default
    private boolean actif = true;

    /**
     * Faut-il exiger NOTRE code à six chiffres en plus de la connexion du fournisseur ?
     *
     * <p>Non par défaut. Un établissement qui délègue l'authentification applique sa propre
     * politique de second facteur ; lui réclamer un code par courriel par-dessus reviendrait à
     * ne lui avoir rien vendu. Le jour où ce fournisseur n'impose aucun second facteur, cette
     * case le rétablit — et c'est une décision qui lui appartient, pas à nous.
     */
    @Column(name = "exiger_otp", nullable = false)
    @Builder.Default
    private boolean exigerOtp = false;

    @Column(name = "cree_le", nullable = false)
    @Builder.Default
    private LocalDateTime creeLe = LocalDateTime.now();

    @Column(name = "modifie_le")
    private LocalDateTime modifieLe;

    /** Renseignée à chaque connexion réussie : une configuration jamais utilisée se repère. */
    @Column(name = "derniere_connexion")
    private LocalDateTime derniereConnexion;

    /** Domaines acceptés, en minuscules et sans vide. Liste vide = aucune restriction. */
    @Transient
    public List<String> domainesAutorises() {
        if (domaines == null || domaines.isBlank()) return List.of();
        return Arrays.stream(domaines.split(","))
                .map(d -> d.trim().toLowerCase(Locale.ROOT))
                .filter(d -> !d.isEmpty())
                .toList();
    }

    /**
     * Ce courriel appartient-il à un domaine accepté ?
     *
     * <p>Sans restriction configurée, tout domaine passe : le rattachement du compte reste alors
     * le seul verrou, et il suffit — un courriel inconnu de l'établissement n'ouvre rien. La
     * liste est un second verrou, pour qui a un fournisseur capable d'authentifier plus large
     * que son propre personnel.
     */
    @Transient
    public boolean accepteLeDomaine(String email) {
        List<String> autorises = domainesAutorises();
        if (autorises.isEmpty()) return true;
        if (email == null) return false;
        int arobase = email.lastIndexOf('@');
        if (arobase < 0 || arobase == email.length() - 1) return false;
        String domaine = email.substring(arobase + 1).toLowerCase(Locale.ROOT);
        // Comparaison stricte : « esatic.ci » n'autorise pas « faux-esatic.ci », ni
        // « esatic.ci.attaquant.com ». Un sous-domaine légitime s'ajoute explicitement.
        return autorises.contains(domaine);
    }
}
