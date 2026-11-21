package ci.esatic.sigep.integration;

import ci.esatic.sigep.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seuls les scripts émis par le serveur pour la réponse courante s'exécutent. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PolitiqueScriptsTest {

    @MockitoBean private DataInitializer dataInitializer;
    @Autowired private MockMvc mockMvc;

    private static final Pattern NONCE_ENTETE = Pattern.compile("'nonce-([A-Za-z0-9+/=]+)'");

    private void verifierPage(String url) throws Exception {
        MvcResult r = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        String csp = r.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("'strict-dynamic'").contains("object-src 'none'");
        Matcher m = NONCE_ENTETE.matcher(csp);
        assertThat(m.find()).isTrue();
        String nonce = m.group(1);

        String html = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Matcher scripts = Pattern.compile("<script\\b[^>]*>").matcher(html);
        int n = 0;
        while (scripts.find()) {
            n++;
            assertThat(scripts.group()).as(url + " : " + scripts.group()).contains("nonce=\"" + nonce + "\"");
        }
        assertThat(n).isPositive();
    }

    @Test
    void lesPagesPubliquesEtLaConnexion_portentLeNonceDeLaReponse() throws Exception {
        verifierPage("/admin-login");
        verifierPage("/");
        verifierPage("/inscription");
    }

    @Test
    void deuxReponses_nOntPasLeMemeNonce() throws Exception {
        String a = mockMvc.perform(get("/admin-login")).andReturn().getResponse().getHeader("Content-Security-Policy");
        String b = mockMvc.perform(get("/admin-login")).andReturn().getResponse().getHeader("Content-Security-Policy");
        assertThat(a).isNotEqualTo(b);
    }

    /** Garde-fou : un gestionnaire en ligne ou un script sans nonce serait bloqué en production. */
    @Test
    void aucunGabarit_nUtiliseDeScriptEnLigneNonAutorise() throws IOException {
        Pattern gestionnaire = Pattern.compile("\\son[a-z]+\\s*=\\s*\"|javascript:", Pattern.CASE_INSENSITIVE);
        Pattern script = Pattern.compile("<script\\b[^>]*>");
        List<String> fautes = new ArrayList<>();
        try (Stream<Path> fichiers = Files.walk(Path.of("src/main/resources/templates"))) {
            for (Path f : fichiers.filter(p -> p.toString().endsWith(".html")).toList()) {
                String[] lignes = Files.readString(f, StandardCharsets.UTF_8).split("\n");
                for (int i = 0; i < lignes.length; i++) {
                    String l = lignes[i];
                    if (l.trim().startsWith("//")) continue; // commentaire de script
                    if (gestionnaire.matcher(l).find()) fautes.add(f + ":" + (i + 1) + " " + l.trim());
                    Matcher s = script.matcher(l);
                    while (s.find()) if (!s.group().contains("th:nonce")) fautes.add(f + ":" + (i + 1) + " " + s.group());
                }
            }
        }
        assertThat(fautes).isEmpty();
    }

    /**
     * Garde-fou : la console change de page sans recharger, et ne réexécute que les scripts
     * marqués {@code data-page-script}. Un script de page sans la marque ne s'exécute qu'au
     * premier chargement : arrivé par le menu, la page s'affiche mais ses boutons ne font rien.
     * Seul le script commun du fragment de la console en est exempt (il ne doit pas être rejoué).
     */
    @Test
    void lesScriptsDesPagesDeConsole_sontRejouesParLaNavigation() throws IOException {
        Pattern script = Pattern.compile("<script\\b[^>]*>");
        List<String> fautes = new ArrayList<>();
        for (String dossier : List.of("admin", "plateforme")) {
            try (Stream<Path> fichiers = Files.walk(Path.of("src/main/resources/templates", dossier))) {
                for (Path f : fichiers.filter(p -> p.toString().endsWith(".html")).toList()) {
                    String nom = f.getFileName().toString();
                    // Fragments communs, et la connexion, servie hors de la navigation instantanée.
                    if (nom.equals("fragments.html") || nom.equals("login.html")) continue;
                    Matcher s = script.matcher(Files.readString(f, StandardCharsets.UTF_8));
                    while (s.find()) if (!s.group().contains("data-page-script")) fautes.add(f + " " + s.group());
                }
            }
        }
        assertThat(fautes).isEmpty();
    }
}
