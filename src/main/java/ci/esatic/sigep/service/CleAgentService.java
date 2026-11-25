package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.CleAgent;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.CleAgentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/** Émission, reconnaissance et révocation des clés d'agent. */
@Service
@RequiredArgsConstructor
public class CleAgentService {

    /** Distinct du préfixe des clés d'API : on sait d'un coup d'œil laquelle on tient. */
    public static final String PREFIXE = "sigep_agent_";

    private static final SecureRandom ALEA = new SecureRandom();

    private final CleAgentRepository cleAgentRepository;

    /**
     * Émet une clé pour l'administrateur donné, et ne la rend qu'ici, une seule fois.
     *
     * @return la clé en clair, à afficher une fois et à ne jamais journaliser
     */
    @Transactional
    public String emettre(User administrateur, String libelle) {
        byte[] octets = new byte[30];
        ALEA.nextBytes(octets);
        String cle = PREFIXE + Base64.getUrlEncoder().withoutPadding().encodeToString(octets);

        cleAgentRepository.save(CleAgent.builder()
                .libelle(libelle == null || libelle.isBlank() ? "Agent sans nom" : libelle.trim())
                .empreinte(CleApiService.empreinte(cle))
                .prefixe(cle.substring(0, Math.min(PREFIXE.length() + 4, cle.length())))
                .etablissementId(administrateur.getEtablissement().getId())
                .userId(administrateur.getId())
                .creeeLe(LocalDateTime.now())
                .build());
        return cle;
    }

    /** Reconnaît une clé active et note son utilisation. */
    @Transactional
    public Optional<CleAgent> reconnaitre(String cleEnClair) {
        if (cleEnClair == null || cleEnClair.isBlank()) {
            return Optional.empty();
        }
        Optional<CleAgent> trouvee = cleAgentRepository
                .chercherActiveParEmpreinte(CleApiService.empreinte(cleEnClair.trim()));
        trouvee.ifPresent(c -> {
            c.setDerniereUtilisation(LocalDateTime.now());
            cleAgentRepository.save(c);
        });
        return trouvee;
    }

    /** Révoque sans supprimer : la ligne garde la trace de ce qui a existé. */
    @Transactional
    public boolean revoquer(Long id) {
        return cleAgentRepository.findById(id).map(c -> {
            if (c.estActive()) {
                c.setRevoqueeLe(LocalDateTime.now());
                cleAgentRepository.save(c);
            }
            return true;
        }).orElse(false);
    }
}
