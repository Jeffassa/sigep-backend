package ci.esatic.sigep.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Garde le service éveillé sur un hébergement qui l'endort après 15 minutes sans requête : un
 * enseignant ne doit pas attendre son premier scan du matin, et les relances planifiées doivent
 * tourner. L'appel passe par l'adresse publique, seule visible de l'hébergeur, et interroge aussi
 * la base. Sans adresse configurée, ce service ne fait rien.
 */
@Service
@Lazy(false)
@Slf4j
public class ReveilService {

    @Value("${app.keep-alive.url:}")
    private String adresse;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void garderEveille() {
        if (adresse == null || adresse.isBlank()) return;
        try {
            HttpRequest requete = HttpRequest.newBuilder(URI.create(adresse.replaceAll("/+$", "") + "/actuator/health"))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            int statut = client.send(requete, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (statut >= 400) log.warn("Maintien en éveil : réponse {}", statut);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Maintien en éveil impossible : {}", e.getMessage());
        }
    }
}
