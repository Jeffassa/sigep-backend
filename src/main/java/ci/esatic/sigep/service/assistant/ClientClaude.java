package ci.esatic.sigep.service.assistant;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import org.springframework.stereotype.Component;

/**
 * Appel au modèle de l'assistant (SDK officiel Anthropic). Isolé pour que les tests le remplacent :
 * aucun test n'appelle le vrai service.
 */
@Component
public class ClientClaude {

    private volatile AnthropicClient client;

    /** Une clé est-elle disponible ? Sans elle, l'assistant reste fermé. */
    public boolean configure() {
        String cle = System.getenv("ANTHROPIC_API_KEY");
        return cle != null && !cle.isBlank();
    }

    public Message envoyer(MessageCreateParams params) {
        if (client == null) {
            synchronized (this) {
                if (client == null) client = AnthropicOkHttpClient.fromEnv(); // lit ANTHROPIC_API_KEY
            }
        }
        return client.messages().create(params);
    }
}
