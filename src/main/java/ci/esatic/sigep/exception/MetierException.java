package ci.esatic.sigep.exception;

/**
 * Erreur de règle MÉTIER (400) portant un CODE stable, exploitable par le client
 * (mobile) pour réagir précisément — au lieu d'une IllegalArgumentException générique
 * indistincte. Le message reste destiné à l'affichage ; le code au traitement.
 */
public class MetierException extends RuntimeException {

    private final String code;

    public MetierException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
