package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.entity.ApplicationMobile;
import ci.esatic.sigep.security.ClientIpResolver;
import ci.esatic.sigep.service.ApplicationMobileService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Téléchargement public de l'application Android : le lien que l'établissement envoie à ses
 * enseignants. Ouvert sans connexion, puisque l'enseignant n'a pas encore l'application.
 */
@Controller
@RequiredArgsConstructor
public class ApplicationMobileController {

    public static final String CHEMIN = "/telecharger/sigep-android.apk";

    /**
     * Plafond par adresse et par heure. Assez large pour tout un établissement derrière la même
     * box (le Wi-Fi d'une école), assez bas pour qu'une boucle ne vide pas la bande passante.
     */
    static final int PLAFOND_PAR_HEURE = 60;

    static final MediaType TYPE_APK = MediaType.parseMediaType("application/vnd.android.package-archive");

    private final ApplicationMobileService service;
    private final ClientIpResolver clientIpResolver;

    private final Cache<String, AtomicInteger> compteurs = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1)).maximumSize(50_000).build();

    @GetMapping(CHEMIN)
    public ResponseEntity<StreamingResponseBody> telecharger(HttpServletRequest request) {
        Optional<ApplicationMobile> courante = service.courante().filter(a -> a.getMorceaux() > 0);
        if (courante.isEmpty()) return ResponseEntity.notFound().build();

        String ip = clientIpResolver.resolve(request);
        if (compteurs.get(ip == null ? "?" : ip, k -> new AtomicInteger()).incrementAndGet() > PLAFOND_PAR_HEURE) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        ApplicationMobile app = courante.get();
        StreamingResponseBody corps = sortie -> service.ecrire(app, sortie);
        return ResponseEntity.ok()
                .contentType(TYPE_APK)
                .contentLength(app.getTaille())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(ApplicationMobileService.nomFichier(app)).build().toString())
                .header("X-Content-SHA256", app.getSha256())
                .body(corps);
    }
}
