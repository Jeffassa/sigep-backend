package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.CleApi;
import ci.esatic.sigep.repository.CleApiRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/** Émission, vérification et révocation des clés de l'API publique. */
@Service
@RequiredArgsConstructor
public class CleApiService {

    /** Reconnaissable d'un coup d'œil dans un fichier de configuration ou un journal. */
    public static final String PREFIXE = "sigep_";

    private static final SecureRandom ALEA = new SecureRandom();

    private final CleApiRepository cleApiRepository;

    /**
     * Émet une clé et ne la rend qu'ICI, une seule fois.
     *
     * <p>Seule l'empreinte est conservée : la clé en clair n'existe plus nulle part après cet
     * appel. C'est ce qui permet d'affirmer à l'administrateur que personne d'autre que lui ne
     * la connaît — y compris nous.
     *
     * @return la clé en clair, à afficher une fois et à ne jamais journaliser
     */
    @Transactional
    public String emettre(String libelle) {
        byte[] octets = new byte[30];
        ALEA.nextBytes(octets);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
        String cle = PREFIXE + secret;

        cleApiRepository.save(CleApi.builder()
                .libelle(libelle == null || libelle.isBlank() ? "Clé sans nom" : libelle.trim())
                .empreinte(empreinte(cle))
                .prefixe(cle.substring(0, Math.min(12, cle.length())))
                .creeeLe(LocalDateTime.now())
                .build());

        return cle;
    }

    /**
     * Reconnaît une clé et rend l'établissement qu'elle ouvre.
     *
     * <p>La date de dernière utilisation est mise à jour au passage : c'est ce qui permet de
     * repérer une clé oubliée depuis des mois, ou utilisée alors qu'elle ne devrait plus l'être.
     */
    @Transactional
    public Optional<CleApi> reconnaitre(String cleEnClair) {
        if (cleEnClair == null || cleEnClair.isBlank()) {
            return Optional.empty();
        }
        Optional<CleApi> trouvee = cleApiRepository.chercherActiveParEmpreinte(empreinte(cleEnClair.trim()));
        trouvee.ifPresent(c -> {
            c.setDerniereUtilisation(LocalDateTime.now());
            cleApiRepository.save(c);
        });
        return trouvee;
    }

    @Transactional
    public boolean revoquer(Long id) {
        return cleApiRepository.findById(id).map(c -> {
            // On révoque sans supprimer : la ligne garde la trace de ce qui a existé, et de sa
            // dernière utilisation. Effacer priverait d'une information utile après un incident.
            c.setRevoqueeLe(LocalDateTime.now());
            cleApiRepository.save(c);
            return true;
        }).orElse(false);
    }

    /** SHA-256 hexadécimal, comme pour le code de secours de l'administration. */
    public static String empreinte(String valeur) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(valeur.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
