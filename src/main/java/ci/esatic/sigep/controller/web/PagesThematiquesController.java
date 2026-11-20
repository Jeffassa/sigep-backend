package ci.esatic.sigep.controller.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Pages thématiques publiques : une page par besoin qu'un établissement tape dans un moteur de
 * recherche. L'accueil seul ne pouvait se classer que sur une poignée de mots ; chacune de ces
 * pages répond à une recherche précise et renvoie vers les autres et vers l'inscription.
 *
 * <p>Ajouter une page ici impose de l'ajouter aussi à {@link #CHEMINS} (accès public et cache) et
 * au {@code sitemap.xml}.
 */
@Controller
public class PagesThematiquesController {

    /** Les chemins de ces pages, ouverts à tous et mis en cache comme les autres pages publiques. */
    public static final String[] CHEMINS = {
            "/emargement-qr-code",
            "/suivi-heures-vacataires",
            "/etat-heures-paie-enseignants",
            "/logiciel-emargement-cote-d-ivoire",
    };

    @GetMapping("/emargement-qr-code")
    public String emargementQrCode() {
        return "public/guides/emargement-qr-code";
    }

    @GetMapping("/suivi-heures-vacataires")
    public String heuresVacataires() {
        return "public/guides/suivi-heures-vacataires";
    }

    @GetMapping("/etat-heures-paie-enseignants")
    public String etatHeuresPaie() {
        return "public/guides/etat-heures-paie-enseignants";
    }

    @GetMapping("/logiciel-emargement-cote-d-ivoire")
    public String coteDIvoire() {
        return "public/guides/logiciel-emargement-cote-d-ivoire";
    }
}
