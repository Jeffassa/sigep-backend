package ci.esatic.sigep.config;

import ci.esatic.sigep.entity.ERole;

import java.util.Map;
import java.util.Set;

/**
 * Ce que chaque profil d'un établissement peut ouvrir. L'administrateur principal a tout et reste
 * seul à gérer l'équipe, le logo et les clés d'API. Les chemins protègent ; le menu ne fait que
 * ne pas proposer ce qui serait refusé.
 */
public final class ProfilsAdmin {

    private ProfilsAdmin() {}

    public static final String[] LECTURE_COMMUNE = {
            "/admin/dashboard", "/admin/statistiques", "/admin/api/stats", "/admin/api/stats/**",
            "/admin/rapports", "/admin/rapports/telecharger-zip", "/admin/rapports/synthese",
            "/admin/rapports/*/telecharger", "/admin/etablissement/logo",
            // La page reste visible : c'est là que renvoie un établissement suspendu.
            "/admin/abonnement"
    };

    public static final String[] LECTURE_DIRECTION = {
            "/admin/enseignants", "/admin/referentiels", "/admin/calendrier", "/admin/alertes"
    };

    public static final String[] SCOLARITE = {
            "/admin/enseignants", "/admin/enseignants/**", "/admin/messages", "/admin/referentiels",
            "/admin/matieres", "/admin/matieres/**", "/admin/classes", "/admin/classes/**",
            "/admin/salles", "/admin/salles/**", "/admin/campus", "/admin/campus/**",
            "/admin/planning/**", "/admin/calendrier", "/admin/calendrier/**", "/admin/alertes",
            "/admin/seances/**", "/admin/rattrapages/**", "/admin/emargements/**",
            "/admin/ecran-emargement", "/admin/ecran-emargement/ouvrir"
    };

    /** Profils qu'un administrateur principal peut attribuer, avec leur libellé. */
    public static final Map<ERole, String> LIBELLES = Map.of(
            ERole.ROLE_DIRECTION, "Direction",
            ERole.ROLE_SCOLARITE, "Scolarité",
            ERole.ROLE_COMPTABILITE, "Comptabilité");

    private static final Map<ERole, Set<String>> MENUS = Map.of(
            ERole.ROLE_DIRECTION, Set.of("dashboard", "enseignants", "rapports", "paie", "referentiels",
                    "calendrier", "statistiques", "alertes", "abonnement"),
            ERole.ROLE_SCOLARITE, Set.of("dashboard", "enseignants", "messages", "rapports", "referentiels",
                    "calendrier", "statistiques", "alertes", "abonnement"),
            ERole.ROLE_COMPTABILITE, Set.of("dashboard", "rapports", "paie", "statistiques", "abonnement"));

    /** Onglets à montrer ; null pour l'administrateur principal, qui les voit tous. */
    public static Set<String> menu(ERole profil) {
        return profil == null ? null : MENUS.get(profil);
    }
}
