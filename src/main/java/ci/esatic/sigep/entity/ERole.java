package ci.esatic.sigep.entity;

public enum ERole {
    ROLE_ADMIN,
    ROLE_ENSEIGNANT,
    /** Propriétaire de la plateforme SaaS : espace /plateforme (gestion de tous les établissements). */
    ROLE_SUPER_ADMIN,
    /** Direction de l'établissement : consulte tout, ne modifie rien. */
    ROLE_DIRECTION,
    /** Scolarité : enseignants, emploi du temps, calendrier, émargements et alertes. */
    ROLE_SCOLARITE,
    /** Comptabilité : paie et abonnement. */
    ROLE_COMPTABILITE
}
