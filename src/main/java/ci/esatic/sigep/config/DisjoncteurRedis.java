package ci.esatic.sigep.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Coupe Redis de l'application dès qu'il cesse de répondre, puis le reteste de temps en temps.
 *
 * <p>Pourquoi : un Redis qui ne répond plus ne renvoie pas d'erreur, il laisse attendre. Chaque
 * lecture du cache attendait donc son délai de connexion (2 s) avant de relire la base, et une
 * page en fait plusieurs. Toute la console mettait plusieurs secondes par clic ; les pages
 * préchargées au survol occupaient le serveur autant de temps, et les clics s'empilaient
 * derrière elles jusqu'à sembler ne jamais aboutir.
 *
 * <p>Désormais, le premier échec ouvre le circuit : pendant {@link #PAUSE}, plus aucune requête
 * ne touche Redis, et le cache passe en mémoire locale. Ensuite, UNE seule requête reteste
 * Redis ; s'il répond, le circuit se referme, sinon il reste ouvert pour une nouvelle pause.
 */
@Component
@Slf4j
public class DisjoncteurRedis {

    /** Plus longue que la durée de vie des entrées (20 s) : au retour, rien d'ancien n'est relu. */
    static final Duration PAUSE = Duration.ofSeconds(30);

    private final Duration pause;
    private final LongSupplier horloge;

    /** Instant (ms) jusqu'auquel Redis est écarté ; 0 = circuit fermé, Redis utilisé. */
    private volatile long ecarteJusqua;
    /** Une seule requête reteste Redis à la fin d'une pause ; les autres ne l'attendent pas. */
    private final AtomicBoolean essaiEnCours = new AtomicBoolean();

    public DisjoncteurRedis() {
        this(PAUSE, System::currentTimeMillis);
    }

    DisjoncteurRedis(Duration pause, LongSupplier horloge) {
        this.pause = pause;
        this.horloge = horloge;
    }

    /** Vrai si cette requête peut s'adresser à Redis. Doit être suivi de {@link #reussite} ou {@link #echec}. */
    public boolean autorise() {
        long fin = ecarteJusqua;
        if (fin == 0) return true;
        if (horloge.getAsLong() < fin) return false;
        return essaiEnCours.compareAndSet(false, true);
    }

    public void reussite() {
        if (ecarteJusqua != 0) {
            ecarteJusqua = 0;
            log.info("Redis répond de nouveau : le cache partagé est rétabli.");
        }
        essaiEnCours.set(false);
    }

    public void echec(Throwable e) {
        boolean dejaOuvert = ecarteJusqua != 0;
        ecarteJusqua = horloge.getAsLong() + pause.toMillis();
        essaiEnCours.set(false);
        if (!dejaOuvert) {
            log.warn("Redis ne répond pas : écarté pendant {} s, le cache passe en mémoire locale. Cause : {}",
                    pause.toSeconds(), NestedExceptionUtils.getMostSpecificCause(e).toString());
        }
    }

    /** Vrai tant que Redis est écarté. */
    public boolean ouvert() {
        return ecarteJusqua != 0;
    }
}
