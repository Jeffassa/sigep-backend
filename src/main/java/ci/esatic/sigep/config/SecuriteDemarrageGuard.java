package ci.esatic.sigep.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * Garde-fou de démarrage (fail-closed) contre l'utilisation accidentelle d'une clé
 * secrète de DÉVELOPPEMENT en production.
 *
 * <p>Les valeurs de repli de {@code application-dev.yml} sont versionnées donc publiques :
 * si l'application tournait avec elles hors du profil {@code dev}, n'importe qui pourrait
 * forger des jetons JWT (y compris admin). Ce garde-fou refuse le démarrage dans ce cas.
 *
 * <p>Combiné à l'absence de profil par défaut ({@code application.yml}), un déploiement mal
 * configuré s'arrête au lieu de servir avec une clé forgeable.
 */
@Component
public class SecuriteDemarrageGuard {

    /** Clés de repli de DÉV (cf. application-dev.yml) — publiques, interdites hors profil dev. */
    private static final Set<String> SECRETS_DEV_CONNUS = Set.of(
            "ZGV2LXNpZ2VwLWp3dC1zZWNyZXQta2V5LW5vdC1mb3ItcHJvZHVjdGlvbi0yMDI2",
            "ZGV2LXFyLXNpZ2VwLXNlY3JldC1rZXktbm90LWZvci1wcm9kdWN0aW9uLTIwMjY="
    );

    private final Environment env;
    private final String jwtSecret;
    private final String qrSecret;

    public SecuriteDemarrageGuard(Environment env,
                                  @Value("${app.jwt.secret:}") String jwtSecret,
                                  @Value("${app.jwt.qr-secret:}") String qrSecret) {
        this.env = env;
        this.jwtSecret = jwtSecret;
        this.qrSecret = qrSecret;
    }

    @PostConstruct
    void verifierSecrets() {
        boolean profilDev = Arrays.asList(env.getActiveProfiles()).contains("dev");
        if (profilDev) {
            return; // En dev local, les clés de dev sont attendues et sans danger.
        }
        if (SECRETS_DEV_CONNUS.contains(jwtSecret) || SECRETS_DEV_CONNUS.contains(qrSecret)) {
            throw new IllegalStateException(
                    "Démarrage refusé (sécurité) : une clé secrète de DÉVELOPPEMENT (publique, "
                  + "versionnée) est utilisée hors du profil 'dev'. Définissez des valeurs propres "
                  + "à l'environnement pour JWT_SECRET et JWT_QR_SECRET, et activez le bon profil "
                  + "(SPRING_PROFILES_ACTIVE=prod en production).");
        }
    }
}
