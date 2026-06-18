package ci.esatic.sigep.exception;

/**
 * Levée lorsqu'un enseignant authentifié (identifiants corrects) tente de se
 * connecter alors que son compte n'a pas encore été validé par l'administration
 * (statut PENDING) ou a été refusé (statut REJECTED). Mappée sur un HTTP 403.
 */
public class CompteNonValideException extends RuntimeException {
    public CompteNonValideException(String message) {
        super(message);
    }
}
