package ci.esatic.sigep.service.assistant;

import ci.esatic.sigep.entity.AssistantConso;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.repository.AssistantConsoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** Borne la consommation de l'assistant : un nombre de messages par établissement et par jour. */
@Service
@RequiredArgsConstructor
public class AssistantConsoService {

    private final AssistantConsoRepository repository;

    @Value("${app.ai.assistant.max-requetes-jour:100}")
    private int maxRequetesJour;

    @Transactional(readOnly = true)
    public int restantes(Long etablissementId) {
        int faites = repository.findByEtablissementIdAndJour(etablissementId, LocalDate.now())
                .map(AssistantConso::getRequetes).orElse(0);
        return Math.max(0, maxRequetesJour - faites);
    }

    public void verifier(Long etablissementId) {
        if (restantes(etablissementId) <= 0) {
            throw new MetierException("QUOTA_ASSISTANT", "L'assistant a atteint sa limite du jour ("
                    + maxRequetesJour + " messages). Il sera de nouveau disponible demain.");
        }
    }

    @Transactional
    public void noterMessage(Long etablissementId) {
        AssistantConso c = ligne(etablissementId);
        c.setRequetes(c.getRequetes() + 1);
    }

    @Transactional
    public void noterJetons(Long etablissementId, long entree, long sortie) {
        AssistantConso c = ligne(etablissementId);
        c.setJetonsEntree(c.getJetonsEntree() + entree);
        c.setJetonsSortie(c.getJetonsSortie() + sortie);
    }

    private AssistantConso ligne(Long etablissementId) {
        LocalDate jour = LocalDate.now();
        return repository.findByEtablissementIdAndJour(etablissementId, jour)
                .orElseGet(() -> repository.save(AssistantConso.builder().etablissementId(etablissementId).jour(jour).build()));
    }
}
