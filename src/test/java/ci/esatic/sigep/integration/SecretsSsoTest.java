package ci.esatic.sigep.integration;

import ci.esatic.sigep.security.sso.ChiffrementSecret;
import ci.esatic.sigep.security.sso.EmetteurAutorise;
import ci.esatic.sigep.security.sso.SsoIndisponibleException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Les deux pièces du SSO qui ne parlent qu'à elles-mêmes : le chiffrement du secret client, et
 * le filtre des adresses que le serveur accepte d'aller interroger.
 *
 * <p>Pas de contexte Spring, et aucun appel réseau : chaque cas est choisi pour se décider sans
 * résolution de nom — adresse littérale, ou refus prononcé avant toute résolution. Un test de
 * sécurité qui échoue parce qu'on est hors ligne finit par être désactivé.
 */
class SecretsSsoTest {

    /** 32 octets : la taille d'une clé AES-256. */
    private static final String CLE =
            Base64.getEncoder().encodeToString("sigep-test-sso-key-32-octets!!!!".getBytes());

    private static ChiffrementSecret avecCle() {
        return new ChiffrementSecret(CLE);
    }

    // ───────────────────────────── Le chiffrement ─────────────────────────────

    @Test
    void unSecretChiffre_seRelit() {
        ChiffrementSecret c = avecCle();
        String chiffre = c.chiffrer("GOCSPX-secret-du-client");

        assertThat(chiffre).isNotEqualTo("GOCSPX-secret-du-client");
        assertThat(c.dechiffrer(chiffre)).isEqualTo("GOCSPX-secret-du-client");
    }

    @Test
    void deuxChiffrementsDuMemeSecret_neSeRessemblentPas() {
        ChiffrementSecret c = avecCle();

        // Un vecteur d'initialisation aléatoire par chiffrement : sans lui, deux établissements
        // qui emploieraient le même secret produiraient la même ligne, et l'on saurait, en
        // lisant la base, qu'ils ont le même — sans avoir rien déchiffré.
        assertThat(c.chiffrer("meme-secret")).isNotEqualTo(c.chiffrer("meme-secret"));
    }

    @Test
    void uneLigneAlteree_estRefusee_plutotQueRendueFausse() {
        ChiffrementSecret c = avecCle();
        String chiffre = c.chiffrer("secret-authentique");

        // Un octet retourné au milieu du texte chiffré. C'est tout l'intérêt du mode GCM :
        // il AUTHENTIFIE. Sans lui, le déchiffrement rendrait des octets quelconques — et nous
        // enverrions au fournisseur d'identité un secret silencieusement faux, dont l'échec
        // ressemblerait à une panne de leur côté.
        byte[] octets = Base64.getDecoder().decode(chiffre);
        octets[octets.length / 2] ^= 0x01;
        String altere = Base64.getEncoder().encodeToString(octets);

        assertThatThrownBy(() -> c.dechiffrer(altere))
                .isInstanceOf(SsoIndisponibleException.class);
    }

    @Test
    void uneAutreCle_neRelitRien() {
        String chiffre = avecCle().chiffrer("secret");
        ChiffrementSecret autre = new ChiffrementSecret(
                Base64.getEncoder().encodeToString("UNE-AUTRE-CLE-DE-32-OCTETS-EXACT".getBytes()));

        // La conséquence à connaître avant d'adopter le SSO : la clé perdue, les secrets déjà
        // enregistrés sont irrécupérables. Ils se ressaisissent, mais chacun doit le faire.
        assertThatThrownBy(() -> autre.dechiffrer(chiffre))
                .isInstanceOf(SsoIndisponibleException.class);
    }

    @Test
    void sansCle_leSsoEstIndisponible_etLeDitClairement() {
        ChiffrementSecret sansCle = new ChiffrementSecret("");

        assertThat(sansCle.disponible()).isFalse();
        // Défaut FERMÉ : plutôt refuser la fonctionnalité que conserver en clair le secret qui
        // ouvre l'annuaire d'un établissement.
        assertThatThrownBy(() -> sansCle.chiffrer("secret"))
                .isInstanceOf(SsoIndisponibleException.class)
                .hasMessageContaining("APP_SSO_SECRET_KEY");
    }

    @Test
    void uneCleMalFormee_neFaitPasSemblantDeMarcher() {
        // Trop courte, et non base64 : deux façons de se tromper en recopiant une variable
        // d'environnement. Aucune ne doit produire un chiffrement affaibli.
        assertThat(new ChiffrementSecret(Base64.getEncoder().encodeToString("trop-court".getBytes()))
                .disponible()).isFalse();
        assertThat(new ChiffrementSecret("ceci n'est pas du base64 !").disponible()).isFalse();
    }

    @Test
    void uneCleEntreGuillemets_resteUtilisable() {
        // Les interfaces d'hébergeur invitent à coller la valeur entre guillemets, et un
        // copier-coller emporte volontiers un espace. Aucun de ces accidents ne doit
        // transformer une clé valide en « SSO indisponible » inexpliqué.
        assertThat(new ChiffrementSecret("  \"" + CLE + "\"  ").disponible()).isTrue();
    }

    // ──────────────────────── Les adresses qu'on accepte ────────────────────────

    @Test
    void uneAdressePublique_estAcceptee() {
        // Adresse littérale : aucune résolution de nom, donc aucun appel réseau.
        assertThat(EmetteurAutorise.estAcceptable("https://203.0.113.10/idp")).isTrue();
    }

    @Test
    void lAdresseDesMetadonnees_estRefusee() {
        // 169.254.169.254 : l'adresse où les hébergeurs servent les identifiants de la machine.
        // C'est NOTRE serveur qui irait la chercher, et il en rapporterait ses propres clés.
        // C'est le cas d'école de la falsification de requête côté serveur.
        assertThatThrownBy(() -> EmetteurAutorise.verifier("https://169.254.169.254/latest/meta-data"))
                .isInstanceOf(SsoIndisponibleException.class)
                .hasMessageContaining("réseau interne");
    }

    @Test
    void leReseauInterne_estRefuseSousToutesSesFormes() {
        for (String interne : java.util.List.of(
                "https://127.0.0.1/idp",          // boucle locale
                "https://10.0.0.5/idp",           // privée, classe A
                "https://192.168.1.10/idp",       // privée, domestique
                "https://172.16.0.1/idp",         // privée, classe B
                "https://100.64.0.1/idp",         // plage partagée d'opérateur (RFC 6598)
                "https://[::1]/idp",              // boucle locale IPv6
                "https://[fc00::1]/idp")) {       // privée IPv6 (fc00::/7)
            assertThat(EmetteurAutorise.estAcceptable(interne))
                    .as("adresse interne acceptee : " + interne)
                    .isFalse();
        }
    }

    @Test
    void uneAdresseEnClair_estRefusee_avantMemeToutResolution() {
        // Refusé sur le schéma, donc sans résolution de nom : l'absence de réseau ne change
        // rien au résultat. Un échange OpenID Connect en clair exposerait le jeton d'identité.
        assertThatThrownBy(() -> EmetteurAutorise.verifier("http://idp.exemple.ci"))
                .isInstanceOf(SsoIndisponibleException.class)
                .hasMessageContaining("https://");
    }

    @Test
    void uneSaisieVideOuAbsurde_estRefusee() {
        assertThat(EmetteurAutorise.estAcceptable(null)).isFalse();
        assertThat(EmetteurAutorise.estAcceptable("   ")).isFalse();
        assertThat(EmetteurAutorise.estAcceptable("pas une url")).isFalse();
        assertThat(EmetteurAutorise.estAcceptable("https://")).isFalse();
        // Un schéma exotique : le client HTTP n'irait nulle part, mais le refus doit être
        // explicite plutôt que d'échouer plus loin, sans explication.
        assertThat(EmetteurAutorise.estAcceptable("file:///etc/passwd")).isFalse();
    }
}
