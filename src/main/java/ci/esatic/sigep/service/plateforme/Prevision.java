package ci.esatic.sigep.service.plateforme;

import java.util.ArrayList;
import java.util.List;

/**
 * Prévision par tendance sur une série mensuelle : une droite des moindres carrés, prolongée.
 *
 * <p>Volontairement simple. Une plateforme jeune n'a que quelques mois d'historique : un modèle
 * plus riche y lirait du bruit. La droite se calcule ici même, sur nos serveurs, et se vérifie
 * à la main ; l'écran dit toujours quel crédit lui accorder ({@link #fiabilite}).
 */
public final class Prevision {

    private Prevision() {}

    /** Droite ajustée : valeur = origine + pente × rang (rang 0 = premier mois de la série). */
    public record Tendance(double pente, double origine) {
        public double valeur(int rang) {
            return origine + pente * rang;
        }
    }

    public static Tendance tendance(List<? extends Number> serie) {
        int n = serie.size();
        if (n == 0) return new Tendance(0, 0);
        if (n == 1) return new Tendance(0, serie.get(0).doubleValue());
        double sx = 0, sy = 0, sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) {
            double y = serie.get(i).doubleValue();
            sx += i;
            sy += y;
            sxy += i * y;
            sxx += (double) i * i;
        }
        double denominateur = n * sxx - sx * sx;
        double pente = denominateur == 0 ? 0 : (n * sxy - sx * sy) / denominateur;
        return new Tendance(pente, (sy - pente * sx) / n);
    }

    /** Les {@code n} mois suivants, arrondis, jamais sous zéro. */
    public static List<Long> prolonger(List<? extends Number> serie, int n) {
        Tendance t = tendance(serie);
        List<Long> suite = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            suite.add(Math.max(0, Math.round(t.valeur(serie.size() + i))));
        }
        return suite;
    }

    /** Moyenne des {@code n} derniers points (ou de toute la série si elle est plus courte). */
    public static double moyenneRecente(List<? extends Number> serie, int n) {
        if (serie.isEmpty()) return 0;
        int debut = Math.max(0, serie.size() - n);
        double somme = 0;
        for (int i = debut; i < serie.size(); i++) somme += serie.get(i).doubleValue();
        return somme / (serie.size() - debut);
    }

    /**
     * Crédit à accorder à une prévision tirée de cette série : « faible » tant qu'il y a moins de
     * quatre mois réellement renseignés ou moins de huit évènements, « moyenne » jusqu'à six mois,
     * « bonne » au-delà.
     */
    public static String fiabilite(List<? extends Number> serie) {
        long moisRenseignes = serie.stream().filter(v -> v.doubleValue() > 0).count();
        double total = serie.stream().mapToDouble(Number::doubleValue).sum();
        if (moisRenseignes < 4 || total < 8) return "faible";
        if (moisRenseignes < 6) return "moyenne";
        return "bonne";
    }
}
