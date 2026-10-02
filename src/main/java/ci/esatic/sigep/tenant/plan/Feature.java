package ci.esatic.sigep.tenant.plan;

/** Fonctionnalités soumises au plan d'abonnement (gating premium B2B). */
public enum Feature {
    ANALYSE_IA,         // bouton « Analyser avec l'IA »
    RAPPORTS_AVANCES,   // export ZIP, synthèse, historique long
    MULTI_CAMPUS,
    BRANDING,           // logo de l'établissement
    SSO,
    /** Export des heures faites, par enseignant et par mois, a destination du service paie. */
    EXPORT_PAIE,
    API_PUBLIQUE
}
