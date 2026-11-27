package ci.esatic.sigep.security;

import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** L'IP retenue est celle du visiteur, jamais celle de Cloudflare ni celle qu'il écrit lui-même. */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(true, "");

    private static MockHttpServletRequest requete(String pair, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(pair);
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @Test
    void derriereCloudflare_onRetientLeVisiteur() {
        assertThat(resolver.resolve(requete("10.201.3.4", "41.202.10.7, 172.70.1.2"))).isEqualTo("41.202.10.7");
        assertThat(resolver.resolve(requete("10.201.3.4", "41.202.10.7, 2a06:98c0::1"))).isEqualTo("41.202.10.7");
    }

    @Test
    void uneIpEcriteParLAppelant_nEstPasLue() {
        // Cloudflare contourné : la fausse IP est à gauche, la vraie à droite.
        assertThat(resolver.resolve(requete("10.201.3.4", "1.2.3.4, 196.47.20.9"))).isEqualTo("196.47.20.9");
        // Fausse IP « Cloudflare » glissée par l'appelant : la vraie, à droite, l'arrête.
        assertThat(resolver.resolve(requete("10.201.3.4", "1.2.3.4, 104.16.0.1, 196.47.20.9")))
                .isEqualTo("196.47.20.9");
    }

    @Test
    void unAppelDirect_ignoreLEnTete() {
        assertThat(resolver.resolve(requete("196.47.20.9", "1.2.3.4"))).isEqualTo("196.47.20.9");
    }

    @Test
    void lEnTeteEstLuSurLaRequeteDOrigine_memeMasqueeParUneEnveloppe() {
        MockHttpServletRequest brute = requete("10.201.3.4", "41.202.10.7, 172.70.1.2");
        HttpServletRequestWrapper masquee = new HttpServletRequestWrapper(brute) {
            @Override public String getHeader(String name) { return null; }
            @Override public String getRemoteAddr() { return "1.2.3.4"; }
        };
        assertThat(resolver.resolve(masquee)).isEqualTo("41.202.10.7");
    }

    @Test
    void sansConfianceDansLesRelais_seulLePairCompte() {
        ClientIpResolver local = new ClientIpResolver(false, "");
        assertThat(local.resolve(requete("127.0.0.1", "1.2.3.4"))).isEqualTo("127.0.0.1");
    }

    @Test
    void unNomDHote_nEstJamaisResolu() {
        assertThat(resolver.resolve(requete("10.0.0.1", "exemple.com"))).isEqualTo("10.0.0.1");
    }
}
