package ci.esatic.sigep.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Compteur de trafic de la dernière heure, en mémoire.
 *
 * <p><b>Pourquoi pas en base ?</b> Écrire une ligne par requête reviendrait à doubler la charge
 * de la base pour produire une courbe : le compteur coûterait plus cher que ce qu'il mesure. Les
 * évènements de SÉCURITÉ, eux, sont rares et méritent d'être conservés — c'est le rôle du journal.
 *
 * <p>Conséquence assumée : ces chiffres repartent de zéro à chaque redémarrage de l'application,
 * et ne valent que pour l'instance qui répond. Ils servent à voir ce qui se passe MAINTENANT, pas
 * à tenir une comptabilité.
 *
 * <p>Anneau de 60 cases, une par minute. La case correspondant à la minute courante est remise à
 * zéro dès qu'on y revient une heure plus tard, ce qui évite d'additionner deux tours de cadran.
 */
@Component
public class CompteurTrafic {

    private static final int MINUTES = 60;

    private final AtomicLongArray requetes = new AtomicLongArray(MINUTES);
    private final AtomicLongArray refus = new AtomicLongArray(MINUTES);
    /** Minute absolue occupant chaque case : sert à détecter qu'une case est périmée. */
    private final AtomicLongArray marqueur = new AtomicLongArray(MINUTES);

    public void compterRequete() {
        int i = preparerCase();
        requetes.incrementAndGet(i);
    }

    public void compterRefus() {
        int i = preparerCase();
        refus.incrementAndGet(i);
    }

    private int preparerCase() {
        long minuteCourante = Instant.now().getEpochSecond() / 60;
        int i = (int) (minuteCourante % MINUTES);
        if (marqueur.get(i) != minuteCourante) {
            // Case du tour précédent : on l'ouvre à neuf. compareAndSet évite que deux fils
            // concurrents la vident tous les deux et perdent un incrément au passage.
            if (marqueur.compareAndSet(i, marqueur.get(i), minuteCourante)) {
                requetes.set(i, 0);
                refus.set(i, 0);
            }
        }
        return i;
    }

    /** Les 60 dernières minutes, de la plus ancienne à la plus récente. */
    public List<Minute> derniereHeure() {
        long minuteCourante = Instant.now().getEpochSecond() / 60;
        List<Minute> serie = new ArrayList<>(MINUTES);
        for (int decalage = MINUTES - 1; decalage >= 0; decalage--) {
            long minute = minuteCourante - decalage;
            int i = (int) (minute % MINUTES);
            boolean valide = marqueur.get(i) == minute;
            serie.add(new Minute(decalage, valide ? requetes.get(i) : 0, valide ? refus.get(i) : 0));
        }
        return serie;
    }

    public long totalRequetes() {
        return derniereHeure().stream().mapToLong(Minute::requetes).sum();
    }

    public long totalRefus() {
        return derniereHeure().stream().mapToLong(Minute::refus).sum();
    }

    /** Pic de requêtes sur une minute : révèle une rafale qu'une moyenne horaire lisserait. */
    public long picParMinute() {
        return derniereHeure().stream().mapToLong(Minute::requetes).max().orElse(0);
    }

    /** @param ilYA nombre de minutes écoulées depuis cette mesure (0 = minute en cours). */
    public record Minute(int ilYA, long requetes, long refus) {}
}
