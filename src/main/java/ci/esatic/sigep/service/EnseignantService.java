package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.response.EnseignantResponse;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EnseignantService {

    private final EnseignantRepository enseignantRepository;
    private final MailService mailService;
    private final RefreshTokenService refreshTokenService;
    private final EtablissementCourantService etablissementCourantService;
    private final ci.esatic.sigep.mapper.EnseignantMapper enseignantMapper;
    private final CompteEnseignantService compteEnseignantService;
    private final ci.esatic.sigep.repository.EtablissementRepository etablissementRepository;

    public Page<EnseignantResponse> searchEnseignants(String search, String departement,
                                                       StatutEnseignant statut, Pageable pageable) {
        Long tenantId = TenantContext.get();
        Page<Enseignant> page = statut == null
                // null : aucune exclusion. L'API conserve son contrat — elle liste tous les
                // statuts, archives compris ; c'est le parametre `statut` qui sert a filtrer.
                ? enseignantRepository.searchEnseignants(search, departement, null, tenantId, pageable)
                : enseignantRepository.searchEnseignantsByStatut(search, departement, statut.name(), tenantId, pageable);
        return page.map(this::toResponse);
    }

    public EnseignantResponse getById(Long id) {
        Enseignant enseignant = enseignantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "id", id));
        return toResponse(enseignant);
    }

    public EnseignantResponse getByUserId(Long userId) {
        Enseignant enseignant = enseignantRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "userId", userId));
        return toResponse(enseignant);
    }

    @Transactional
    public EnseignantResponse updateStatut(Long id, StatutEnseignant statut) {
        Enseignant enseignant = enseignantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "id", id));
        enseignant.setStatut(statut);
        Enseignant saved = enseignantRepository.save(enseignant);

        // SECURITE : si le compte n'est plus validé (refus/suspension), on coupe immédiatement
        // toutes ses sessions en révoquant ses refresh tokens. L'access token restant
        // (JWT) expire seul sous 15 min.
        if (statut != StatutEnseignant.VALIDATED && saved.getUser() != null) {
            couperAcces(saved);
        }

        // Notifier l'enseignant par e-mail (validation / refus)
        if (statut == StatutEnseignant.VALIDATED || statut == StatutEnseignant.REJECTED) {
            String email = saved.getUser() != null ? saved.getUser().getEmail() : null;
            if (email != null) {
                mailService.notifierStatutCompte(expediteurTenant(), email, saved.getPrenom(),
                        statut == StatutEnseignant.VALIDATED);
            }
        }
        return toResponse(saved);
    }

    /**
     * Le refus d'un établissement ne ferme que ses portes : un vacataire validé ailleurs bascule
     * sur son autre établissement et garde ses sessions.
     */
    private void couperAcces(Enseignant enseignant) {
        var compte = enseignant.getUser();
        enseignantRepository.flush();
        var ailleurs = compteEnseignantService.fiches(compte.getId()).stream()
                .filter(f -> f.statut() == StatutEnseignant.VALIDATED)
                .filter(f -> !java.util.Objects.equals(f.etablissementId(), enseignant.getEtablissementId()))
                .findFirst();
        if (ailleurs.isEmpty()) {
            refreshTokenService.revoquerTout(compte);
            return;
        }
        boolean travaillaitIci = compte.getEtablissement() != null
                && compte.getEtablissement().getId().equals(enseignant.getEtablissementId());
        if (travaillaitIci) {
            compte.setEtablissement(etablissementRepository.findById(ailleurs.get().etablissementId()).orElseThrow());
        }
    }

    /** Expéditeur e-mail propre à l'établissement courant (E9), ou null (repli plateforme). */
    private String expediteurTenant() {
        var etab = etablissementCourantService.courant();
        return etab != null ? etab.getEmailFrom() : null;
    }

    @Transactional
    public void delete(Long id) {
        Enseignant enseignant = enseignantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "id", id));
        var compte = enseignant.getUser();
        enseignantRepository.delete(enseignant);
        enseignantRepository.flush();
        compteEnseignantService.retirerSiPlusUtilise(compte);
    }

    private EnseignantResponse toResponse(Enseignant e) {
        return enseignantMapper.toResponse(e);
    }
}
