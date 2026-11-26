package ci.esatic.sigep.service.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Ce qui part chez le fournisseur d'IA : des codes à la place des personnes. */
class PseudonymiseurTest {

    @Test
    @SuppressWarnings("unchecked")
    void lesChampsPersonnelsDevientDesCodes_etReviennentALArrivee() {
        Pseudonymiseur p = new Pseudonymiseur();
        Object masque = p.masquer(Map.of("success", true, "message", "2 enseignant(s).",
                "data", Map.of("enseignants", List.of(
                        Map.of("id", 4, "nom", "Bamba", "prenom", "Awa", "matricule", "P2026-01", "email", "awa.bamba@ecole.ci"),
                        Map.of("id", 5, "nom", "Kone", "prenom", "Ali", "matricule", "V77")))));
        String texte = masque.toString();
        assertThat(texte).doesNotContain("Bamba", "Awa", "P2026-01", "awa.bamba@ecole.ci", "Kone");
        assertThat(texte).contains("id=4", "@masque.invalid");

        Map<String, Object> premier = ((List<Map<String, Object>>) ((Map<String, Object>)
                ((Map<String, Object>) masque).get("data")).get("enseignants")).get(0);
        String reponse = "J'ai trouvé " + premier.get("prenom") + " " + premier.get("nom") + " (" + premier.get("matricule")
                + ", " + premier.get("email") + ").";
        assertThat(p.retablir(reponse)).isEqualTo("J'ai trouvé Awa Bamba (P2026-01, awa.bamba@ecole.ci).");
    }

    @Test
    void unNomDejaConnu_estMasqueDansLeTexteDeLAdministrateur() {
        Pseudonymiseur p = new Pseudonymiseur();
        p.connaitre("Awa", "Bamba", "P2026-01", "awa.bamba@ecole.ci");
        String masque = p.masquerTexte("Renvoie ses accès à awa.bamba@ecole.ci, c'est-à-dire Awa BAMBA (P2026-01).");
        assertThat(masque).doesNotContain("awa.bamba", "Awa", "BAMBA", "P2026-01");
        assertThat(p.retablir(masque)).isEqualTo("Renvoie ses accès à awa.bamba@ecole.ci, c'est-à-dire Awa Bamba (P2026-01).");
    }

    @Test
    void lesArgumentsDUnOutilSontRetablis_etUnCodeInconnuResteTelQuel() {
        Pseudonymiseur p = new Pseudonymiseur();
        String code = p.code("Courriel", "compta@ecole.ci");
        assertThat(p.retablirTout(Map.of("email", code, "profil", "comptabilite", "ids", List.of(1, 2))))
                .isEqualTo(Map.of("email", "compta@ecole.ci", "profil", "comptabilite", "ids", List.of(1, 2)));
        assertThat(p.retablir("Nom_999 reste Nom_999")).isEqualTo("Nom_999 reste Nom_999");
    }

    @Test
    void uneValeurTropCourte_neRemplacePasLesMotsOrdinaires() {
        Pseudonymiseur p = new Pseudonymiseur();
        p.connaitre("Li", "Bi", "12", null);
        assertThat(p.masquerTexte("Il a 12 séances lundi")).isEqualTo("Il a 12 séances lundi");
    }
}
