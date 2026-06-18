package ci.esatic.sigep.exception;

/** Refresh token absent, expiré ou révoqué : l'utilisateur doit se reconnecter (HTTP 401). */
public class RefreshTokenInvalidException extends RuntimeException {
    public RefreshTokenInvalidException(String message) {
        super(message);
    }
}
