package ci.esatic.sigep.entity;

/** Cycle de vie d'une intention de paiement Mobile Money. */
public enum StatutIntent {
    /** Initiée auprès du fournisseur, en attente de confirmation du client. */
    EN_COURS,
    /** Confirmée par le fournisseur ET créditée (abonnement prolongé). */
    REUSSI,
    /** Refusée par le client ou rejetée par l'opérateur. */
    ECHOUE,
    /** Jamais confirmée dans le délai imparti : on cesse de la suivre. */
    EXPIRE,
    /**
     * Cas nécessitant un ARBITRAGE HUMAIN, jamais une clôture silencieuse : argent
     * probablement encaissé mais non créditable en l'état (montant ou devise divergents),
     * ou verdict impossible à obtenir dans la fenêtre de suivi. Ne JAMAIS transformer
     * ces cas en « échec » : cela reviendrait à affirmer au client qu'il n'a pas été
     * débité alors que son argent est parti.
     */
    A_VERIFIER
}
