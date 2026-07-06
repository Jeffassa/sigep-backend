package ci.esatic.sigep.tenant;

/**
 * Levée quand une entité chargée appartient à un AUTRE établissement que le tenant courant.
 * Couvre le cas du chargement par identifiant ({@code findById}), auquel le filtre Hibernate
 * ne s'applique pas. Rendue en 404 (on ne révèle pas l'existence d'une ressource d'un autre tenant).
 * {@code @ResponseStatus} : les contrôleurs WEB (Thymeleaf) rendent aussi la page d'erreur en 404
 * (et non 500) quand l'exception traverse le rendu ; l'API garde son handler JSON dédié.
 */
@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
public class AccesTenantRefuseException extends RuntimeException {
    public AccesTenantRefuseException() {
        super("Ressource introuvable.");
    }
}
