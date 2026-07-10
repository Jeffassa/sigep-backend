package ci.esatic.sigep.entity;

/**
 * Cycle de validation d'un établissement (SA-2) : toute inscription self-service naît
 * EN_ATTENTE et doit être validée par le SUPER ADMIN avant la première connexion.
 */
public enum StatutEtablissement {
    EN_ATTENTE,
    VALIDE,
    REFUSE
}
