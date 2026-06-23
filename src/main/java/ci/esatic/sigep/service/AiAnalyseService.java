package ci.esatic.sigep.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analyse IA des statistiques (bouton « Analyser avec l'IA ») via le SDK officiel Anthropic.
 * Optionnelle et désactivée par défaut : ne s'active que si app.ai.enabled=true ET la clé
 * ANTHROPIC_API_KEY est présente. Aucune dépendance au démarrage (client créé à la demande).
 */
@Service
public class AiAnalyseService {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final long TTL_MS = 30 * 60 * 1000L; // cache 30 min (évite des appels/coûts répétés)

    @Value("${app.ai.enabled:false}")
    private boolean enabled;

    @Value("${app.ai.model:claude-opus-4-8}")
    private String model;

    private volatile AnthropicClient client; // créé paresseusement
    private final Map<String, Cache> cache = new ConcurrentHashMap<>();

    /** L'analyse IA est-elle utilisable ? (activée + clé API présente) */
    public boolean isEnabled() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return enabled && key != null && !key.isBlank();
    }

    /** Renvoie l'analyse (synthèse + décisions) en texte. Met en cache 30 min par période. */
    public String analyser(LocalDate debut, LocalDate fin, Map<String, Object> stats) {
        if (!isEnabled()) {
            return "Analyse IA non configurée (définir AI_ENABLED=true et ANTHROPIC_API_KEY).";
        }
        String cle = debut + "|" + fin;
        Cache c = cache.get(cle);
        if (c != null && (System.currentTimeMillis() - c.ts) < TTL_MS) {
            return c.texte;
        }
        try {
            String texte = appelClaude(debut, fin, stats);
            cache.put(cle, new Cache(texte, System.currentTimeMillis()));
            return texte;
        } catch (Exception e) {
            return "Analyse IA indisponible pour le moment (" + e.getClass().getSimpleName() + ").";
        }
    }

    private String appelClaude(LocalDate debut, LocalDate fin, Map<String, Object> stats) {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.fromEnv(); // lit ANTHROPIC_API_KEY
                }
            }
        }
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(5000L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEME)
                .addUserMessage(donnees(debut, fin, stats))
                .build();

        Message response = client.messages().create(params);
        StringBuilder sb = new StringBuilder();
        response.content().forEach(block -> block.text().ifPresent(t -> sb.append(t.text())));
        String texte = sb.toString().trim();
        return texte.isEmpty() ? "Aucune analyse renvoyée." : texte;
    }

    private static final String SYSTEME =
            "Tu es un analyste de données pour l'administration de l'etablissement ESATIC. "
          + "On te fournit des statistiques d'emargement (presence des enseignants en cours). "
          + "Reponds en francais, de facon concise et actionnable, en deux parties :\n"
          + "1) SYNTHESE : 2 a 3 phrases sur la situation et la tendance.\n"
          + "2) DECISIONS : 3 a 5 actions priorisees (qui, quoi, pourquoi), de la plus a la moins urgente.\n"
          + "N'invente aucun chiffre : appuie-toi uniquement sur les donnees fournies. Pas de preambule.";

    @SuppressWarnings("unchecked")
    private String donnees(LocalDate debut, LocalDate fin, Map<String, Object> s) {
        StringBuilder b = new StringBuilder();
        b.append("Periode : ").append(debut.format(D)).append(" au ").append(fin.format(D)).append("\n");
        b.append("Taux d'emargement global : ").append(s.get("taux")).append("% ")
                .append("(evolution vs periode precedente : ").append(s.get("tauxDelta")).append(" pts)\n");
        b.append("Seances : ").append(s.get("emargees")).append("/").append(s.get("seances"))
                .append(" emargees · ").append(s.get("heures")).append(" h · ")
                .append(s.get("oublis")).append(" oubli(s) · ")
                .append(s.get("emargesEnRetard")).append(" en retard\n");
        b.append("Enseignants : ").append(s.get("profsActifs"))
                .append(" · Rattrapages en attente : ").append(s.get("rattrapages")).append("\n");

        List<Map<String, Object>> profs = (List<Map<String, Object>>) s.get("parEnseignant");
        if (profs != null && !profs.isEmpty()) {
            b.append("\nPar enseignant (taux) :\n");
            for (Map<String, Object> p : profs) {
                b.append("- ").append(p.get("nom")).append(" : ").append(p.get("taux")).append("% (")
                        .append(p.get("emargees")).append("/").append(p.get("total")).append(")\n");
            }
        }
        ajouterAgg(b, "\nPar classe (taux) :\n", (List<Map<String, Object>>) s.get("parClasse"));
        ajouterAgg(b, "\nPar matiere (taux) :\n", (List<Map<String, Object>>) s.get("parMatiere"));

        List<Map<String, Object>> tend = (List<Map<String, Object>>) s.get("tendance");
        if (tend != null && !tend.isEmpty()) {
            b.append("\nTendance hebdomadaire (taux) : ");
            for (Map<String, Object> t : tend) {
                b.append(t.get("label")).append(":").append(t.get("taux")).append("% ");
            }
            b.append("\n");
        }
        return b.toString();
    }

    private void ajouterAgg(StringBuilder b, String titre, List<Map<String, Object>> liste) {
        if (liste == null || liste.isEmpty()) return;
        b.append(titre);
        for (Map<String, Object> m : liste) {
            b.append("- ").append(m.get("libelle")).append(" : ").append(m.get("taux")).append("% (")
                    .append(m.get("emargees")).append("/").append(m.get("total")).append(")\n");
        }
    }

    private record Cache(String texte, long ts) {}
}
