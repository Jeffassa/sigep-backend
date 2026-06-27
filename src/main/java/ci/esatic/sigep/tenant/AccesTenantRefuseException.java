package ci.esatic.sigep.tenant;

/**
 * Levée quand une entité chargée appartient à un AUTRE établissement que le tenant courant.
 * Couvre le cas du chargement par identifiant ({@code findById}), auquel le filtre Hibernate
 * ne s'applique pas. Rendue en 404 (on ne révèle pas l'existence d'une ressource d'un autre tenant).
 */
public class AccesTenantRefuseException extends RuntimeException {
    public AccesTenantRefuseException() {
        super("Ressource introuvable.");
    }
}
