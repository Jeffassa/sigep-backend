package ci.esatic.sigep.security;

/**
 * Identité d'un appel d'API : une clé, et l'établissement qu'elle ouvre.
 *
 * <p>Ce type est reconnu par l'intercepteur multi-tenant, qui pose à partir de lui le périmètre
 * de la requête. C'est indispensable : l'intercepteur n'active le filtre d'isolation que pour
 * les identités qu'il connaît, et une identité inconnue passerait donc SANS filtre — toutes les
 * données de tous les établissements seraient alors lisibles.
 *
 * @param cleId          identifiant de la clé, pour la traçabilité
 * @param etablissementId seul établissement que cet appel peut lire
 * @param libelle        nom de la clé, repris dans les journaux
 */
public record CleApiPrincipal(Long cleId, Long etablissementId, String libelle) {

    @Override
    public String toString() {
        // Jamais la clé ni son empreinte : cet objet finit dans des journaux.
        return "cle-api:" + cleId + "@etab:" + etablissementId;
    }
}
