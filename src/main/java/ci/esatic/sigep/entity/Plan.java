package ci.esatic.sigep.entity;

/**
 * Plan d'abonnement SaaS d'un établissement (tenant).
 * Sert au découpage des fonctionnalités premium (cf. PlanService, Module 3).
 */
public enum Plan {
    FREE,
    PRO,
    ENTERPRISE
}
