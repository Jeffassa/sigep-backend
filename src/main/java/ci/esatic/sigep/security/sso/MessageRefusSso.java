package ci.esatic.sigep.security.sso;

import java.util.Map;

/**
 * Traduit un motif de refus SSO en une phrase affichable.
 *
 * <p>La table est <b>fermée</b>, et c'est tout l'intérêt : seul un motif codé voyage dans
 * l'adresse, jamais une phrase. Un lien forgé — {@code ?sso_error=Votre+compte+est+compromis,
 * +appelez+le+07...} — afficherait sinon un message d'hameçonnage sur notre propre page de
 * connexion, dans notre charte, et paraîtrait venir de nous. Un motif inconnu retombe sur la
 * phrase générique, qui ne dit rien de plus que ce qui s'est passé.
 *
 * <p>Les messages se partagent en trois familles, et la frontière est délibérée :
 * <ul>
 *   <li>Ce que la personne peut corriger elle-même (reprendre la connexion, utiliser son mot de
 *       passe) est dit clairement.</li>
 *   <li>Ce qui relève de la configuration de l'établissement renvoie à son administrateur.</li>
 *   <li>Ce qui relève du cloisonnement — compte inexistant, compte d'un AUTRE établissement —
 *       reçoit <b>la même phrase</b> : les distinguer apprendrait quelles adresses existent chez
 *       nos autres clients.</li>
 * </ul>
 */
public final class MessageRefusSso {

    private MessageRefusSso() {}

    private static final String GENERIQUE =
            "La connexion par votre organisation n'a pas abouti. Utilisez votre mot de passe, "
            + "ou contactez l'administrateur de votre établissement.";

    /** Même phrase pour « ce compte n'existe pas » et « il appartient à un autre établissement ». */
    private static final String COMPTE_INTROUVABLE =
            "Votre fournisseur d'identité vous a bien reconnu, mais aucun compte de cet "
            + "établissement ne porte cette adresse. Demandez à votre administrateur de créer "
            + "votre accès : le SSO ne crée jamais de compte de lui-même.";

    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("annule",
                    "Connexion annulée. Vous pouvez réessayer, ou utiliser votre mot de passe."),
            Map.entry("echange",
                    "L'échange avec votre fournisseur d'identité n'a pas abouti. Reprenez depuis "
                    + "le début : une tentative laissée en attente trop longtemps expire."),
            Map.entry("inconnu", GENERIQUE),
            Map.entry("jeton", GENERIQUE),
            Map.entry("erreur", GENERIQUE),
            Map.entry("etablissement", GENERIQUE),
            Map.entry("statut",
                    "L'espace de votre établissement n'est pas encore activé."),
            Map.entry("suspendu",
                    "L'accès à l'espace de votre établissement est suspendu. Votre administrateur "
                    + "peut le rétablir depuis la page d'abonnement."),
            Map.entry("plan",
                    "La connexion par votre organisation ne fait pas partie de l'offre souscrite "
                    + "par votre établissement."),
            Map.entry("config",
                    "La connexion par votre organisation n'est pas configurée pour votre "
                    + "établissement."),
            Map.entry("inactif",
                    "La connexion par votre organisation est désactivée pour votre établissement."),
            Map.entry("email",
                    "Votre fournisseur d'identité ne nous a pas transmis d'adresse e-mail. Votre "
                    + "administrateur doit autoriser le partage de l'adresse (portée « email »)."),
            Map.entry("non-verifie",
                    "Votre adresse n'est pas confirmée chez votre fournisseur d'identité. "
                    + "Confirmez-la, puis réessayez."),
            Map.entry("domaine",
                    "Cette adresse n'appartient pas à un domaine autorisé par votre établissement."),
            Map.entry("ambigu",
                    "Plusieurs comptes portent cette adresse. Contactez l'administrateur de votre "
                    + "établissement : il faut en supprimer un."),
            Map.entry("inconnu-compte", COMPTE_INTROUVABLE),
            Map.entry("desactive",
                    "Ce compte est désactivé."),
            Map.entry("super-admin",
                    "Le compte plateforme ne se connecte pas par SSO."),
            Map.entry("role",
                    "Ce compte n'a pas accès à l'administration de l'établissement.")
    );

    /** Phrase à afficher pour ce motif ; générique si le motif est inconnu ou forgé. */
    public static String pour(String motif) {
        if (motif == null || motif.isBlank()) return GENERIQUE;
        return MESSAGES.getOrDefault(motif.trim(), GENERIQUE);
    }
}
