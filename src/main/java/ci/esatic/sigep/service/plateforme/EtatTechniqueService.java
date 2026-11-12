package ci.esatic.sigep.service.plateforme;

import ci.esatic.sigep.entity.SeveriteEvenement;
import ci.esatic.sigep.repository.EvenementSecuriteRepository;
import ci.esatic.sigep.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * L'état des rouages dont dépend la plateforme, lu au moment où la page s'affiche.
 *
 * <p>Aucune de ces vérifications ne doit pouvoir faire échouer le tableau de bord : un rouage
 * en panne s'affiche en alerte, il ne renvoie pas une page d'erreur à celui qui vient justement
 * voir ce qui ne va pas.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EtatTechniqueService {

    public static final String OK = "ok";
    public static final String ALERTE = "alerte";
    public static final String INACTIF = "inactif";

    private static final DateTimeFormatter HORODATAGE = DateTimeFormatter.ofPattern("dd/MM 'à' HH:mm");
    private static final int MEMOIRE_ALERTE_PCT = 85;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<Flyway> flyway;
    private final ObjectProvider<RedisConnectionFactory> redis;
    private final MailService mailService;
    private final EvenementSecuriteRepository evenementSecuriteRepository;

    @Value("${spring.cache.type:simple}")
    private String typeDeCache;

    @Value("${app.security.login-rate-limit.redis-enabled:false}")
    private boolean limitesSurRedis;

    /** Renseignées par l'hébergeur à chaque déploiement ; vides en local. */
    @Value("${RENDER_GIT_COMMIT:}")
    private String commit;

    @Value("${RENDER_GIT_BRANCH:}")
    private String branche;

    public record Composant(String nom, String etat, String detail) {}

    public record EtatTechnique(List<Composant> composants, String version, String enServiceDepuis) {
        public List<Composant> enAlerte() {
            return composants.stream().filter(c -> ALERTE.equals(c.etat())).toList();
        }
    }

    public EtatTechnique etat() {
        List<Composant> composants = new ArrayList<>();
        composants.add(baseDeDonnees());
        composants.add(emails());
        composants.add(cache());
        composants.add(memoire());
        composants.add(securite());
        return new EtatTechnique(composants, version(), enServiceDepuis());
    }

    private Composant baseDeDonnees() {
        try {
            long debut = System.nanoTime();
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            long ms = (System.nanoTime() - debut) / 1_000_000;
            String schema = versionDuSchema();
            return new Composant("Base de données", OK,
                    "Répond en " + ms + " ms" + (schema == null ? "" : " · schéma en version " + schema));
        } catch (Exception ex) {
            log.warn("État technique : la base ne répond pas : {}", ex.getMessage());
            return new Composant("Base de données", ALERTE, "Ne répond pas");
        }
    }

    /**
     * Lue une seule fois. Interroger l'outil de migration coûte une quarantaine de requêtes, et
     * la version du schéma ne change pas tant que l'application tourne : elle n'évolue qu'au
     * démarrage. La relire à chaque affichage ralentissait tout le tableau de bord.
     */
    private volatile String schema;
    private volatile boolean schemaLu;

    private String versionDuSchema() {
        if (schemaLu) return schema;
        try {
            Flyway f = flyway.getIfAvailable();
            schema = (f == null || f.info().current() == null) ? null
                    : f.info().current().getVersion().getVersion();
        } catch (Exception ex) {
            schema = null;
        }
        schemaLu = true;
        return schema;
    }

    private Composant emails() {
        MailService.BilanEnvois b = mailService.bilanEnvois();
        if (!b.active()) {
            return new Composant("E-mails", INACTIF, "Envoi désactivé : les messages sont seulement journalisés");
        }
        if (!b.configure()) {
            return new Composant("E-mails", ALERTE, "Aucun serveur d'envoi configuré");
        }
        if (b.echecs() > 0) {
            return new Composant("E-mails", ALERTE, b.echecs() + " échec(s) depuis le démarrage, le dernier le "
                    + b.dernierEchec().format(HORODATAGE) + " · " + b.envoyes() + " envoyé(s)");
        }
        return new Composant("E-mails", OK, b.envoyes() + " envoyé(s) depuis le démarrage, aucun échec");
    }

    private Composant cache() {
        boolean utilise = limitesSurRedis || "redis".equalsIgnoreCase(typeDeCache);
        RedisConnectionFactory fabrique = redis.getIfAvailable();
        if (!utilise || fabrique == null) {
            return new Composant("Cache", INACTIF, "Redis non utilisé : tout est tenu en mémoire locale");
        }
        try (RedisConnection connexion = fabrique.getConnection()) {
            connexion.ping();
            return new Composant("Cache", OK, "Redis répond");
        } catch (Exception ex) {
            Throwable cause = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(ex);
            log.warn("État technique : Redis ne répond pas : {} — cause : {}", ex.getMessage(), cause.toString());
            return new Composant("Cache", ALERTE, "Redis ne répond pas (" + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : " : " + cause.getMessage()) + ") : repli sur la mémoire locale");
        }
    }

    private Composant memoire() {
        Runtime r = Runtime.getRuntime();
        long utilisee = (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
        long max = r.maxMemory() / (1024 * 1024);
        long pct = max == 0 ? 0 : Math.round(utilisee * 100.0 / max);
        return new Composant("Mémoire", pct >= MEMOIRE_ALERTE_PCT ? ALERTE : OK,
                utilisee + " Mo utilisés sur " + max + " Mo (" + pct + " %)");
    }

    private Composant securite() {
        try {
            long critiques = evenementSecuriteRepository.countBySeveriteAndDateHeureAfter(
                    SeveriteEvenement.CRITIQUE, LocalDateTime.now().minusHours(24));
            return critiques > 0
                    ? new Composant("Sécurité", ALERTE, critiques + " évènement(s) critique(s) en 24 h : à examiner")
                    : new Composant("Sécurité", OK, "Aucun évènement critique en 24 h");
        } catch (Exception ex) {
            return new Composant("Sécurité", INACTIF, "Journal indisponible");
        }
    }

    private String version() {
        if (commit == null || commit.isBlank()) return "Version locale";
        String court = commit.length() > 7 ? commit.substring(0, 7) : commit;
        return "Version " + court + (branche == null || branche.isBlank() ? "" : " · " + branche);
    }

    private String enServiceDepuis() {
        Duration d = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
        if (d.toDays() > 0) return d.toDays() + " j " + d.toHoursPart() + " h";
        if (d.toHours() > 0) return d.toHours() + " h " + d.toMinutesPart() + " min";
        return Math.max(1, d.toMinutes()) + " min";
    }
}
