package ci.esatic.sigep.security;

import ci.esatic.sigep.service.JournalSecuriteService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Les suggestions d'écoles ont leur propre budget, plus étroit que le budget global. */
class RateLimitAnnuaireTest {

    private RateLimitFilter filtre() {
        RateLimitFilter f = new RateLimitFilter();
        ClientIpResolver ip = mock(ClientIpResolver.class);
        when(ip.resolve(any())).thenReturn("203.0.113.9");
        ReflectionTestUtils.setField(f, "clientIpResolver", ip);
        ReflectionTestUtils.setField(f, "compteurTrafic", mock(CompteurTrafic.class));
        ReflectionTestUtils.setField(f, "journalSecurite", mock(JournalSecuriteService.class));
        ReflectionTestUtils.setField(f, "enabled", true);
        ReflectionTestUtils.setField(f, "maxGlobal", 300);
        ReflectionTestUtils.setField(f, "maxKeys", 1000);
        ReflectionTestUtils.setField(f, "maxAnnuaire", 60);
        return f;
    }

    private int statut(RateLimitFilter f, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        MockHttpServletResponse rep = new MockHttpServletResponse();
        f.doFilter(req, rep, new MockFilterChain());
        return rep.getStatus();
    }

    @Test
    void auDelaDe60SuggestionsParMinute_laRechercheEstRefusee() throws Exception {
        RateLimitFilter f = filtre();
        for (int i = 0; i < 60; i++) {
            assertThat(statut(f, "/api/saas/annuaire")).isEqualTo(200);
        }
        assertThat(statut(f, "/api/saas/annuaire")).isEqualTo(429);
        // Le reste du site reste ouvert à cette adresse.
        assertThat(statut(f, "/inscription")).isEqualTo(200);
    }
}
