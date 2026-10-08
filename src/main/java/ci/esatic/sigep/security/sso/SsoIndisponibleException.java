package ci.esatic.sigep.security.sso;

/**
 * Le SSO ne peut pas fonctionner — et ce n'est pas la faute de celui qui se connecte.
 *
 * <p>Clé de chiffrement absente, secret illisible, émetteur refusé : autant de défauts de
 * configuration. Le message porté ici s'adresse à l'administrateur de l'établissement et
 * s'affiche sur son écran de configuration ; il ne contient jamais de secret.
 */
public class SsoIndisponibleException extends RuntimeException {

    public SsoIndisponibleException(String message) {
        super(message);
    }
}
