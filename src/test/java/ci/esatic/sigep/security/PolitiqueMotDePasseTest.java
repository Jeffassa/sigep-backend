package ci.esatic.sigep.security;

import ci.esatic.sigep.exception.MetierException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Les règles d'un mot de passe fort, les mêmes que celles cochées par le formulaire d'inscription. */
class PolitiqueMotDePasseTest {

    @Test
    void unMotDePasseFort_passe() {
        assertThat(PolitiqueMotDePasse.manquements("Kora#Lagune7", "awa@ecole.ci")).isEmpty();
        assertThat(PolitiqueMotDePasse.manquements("Élève-Bété2026", "awa@ecole.ci")).isEmpty();
    }

    @Test
    void chaqueRegleManquante_estNommee() {
        assertThat(PolitiqueMotDePasse.manquements("court1A!", null)).containsExactly("au moins 10 caractères");
        assertThat(PolitiqueMotDePasse.manquements("toutenminuscule1!", null)).containsExactly("une majuscule");
        assertThat(PolitiqueMotDePasse.manquements("TOUTENMAJUSCULE1!", null)).containsExactly("une minuscule");
        assertThat(PolitiqueMotDePasse.manquements("SansChiffre!!", null)).containsExactly("un chiffre");
        assertThat(PolitiqueMotDePasse.manquements("SansSpecial2026", null)).containsExactly("un caractère spécial");
        assertThat(PolitiqueMotDePasse.manquements("", null)).hasSize(5);
    }

    @Test
    void niLAdresseEMail_niUnMotDePasseCourant() {
        assertThat(PolitiqueMotDePasse.manquements("Kouassi#2026x", "kouassi@ecole.ci")).containsExactly("pas votre adresse e-mail");
        assertThat(PolitiqueMotDePasse.manquements("Azerty#2026x", "awa@ecole.ci")).containsExactly("pas un mot de passe courant");
        assertThat(PolitiqueMotDePasse.manquements("MonPassword#1", null)).containsExactly("pas un mot de passe courant");
    }

    @Test
    void exiger_refuseEnDisantCeQuiManque() {
        assertThatThrownBy(() -> PolitiqueMotDePasse.exiger("motdepasse", "a@b.ci"))
                .isInstanceOf(MetierException.class)
                .hasMessageContaining("une majuscule")
                .hasMessageContaining("un chiffre")
                .hasMessageContaining("pas un mot de passe courant");
    }
}
