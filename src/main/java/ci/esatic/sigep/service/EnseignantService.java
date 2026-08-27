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

    public Page<EnseignantResponse> searchEnseignants(String search, String departement,
                                                       StatutEnseignant statut, Pageable pageable) {
        Long tenantId = TenantContext.get();
        Page<Enseignant> page = statut == null
                ? enseignantRepository.searchEnseignants(search, departement, tenantId, pageable)
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
            refreshTokenService.revoquerTout(saved.getUser());
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

    /** Expéditeur e-mail propre à l'établissement courant (E9), ou null (repli plateforme). */
    private String expediteurTenant() {
        var etab = etablissementCourantService.courant();
        return etab != null ? etab.getEmailFrom() : null;
    }

    @Transactional
    public void delete(Long id) {
        if (!enseignantRepository.existsById(id)) {
            throw new ResourceNotFoundException("Enseignant", "id", id);
        }
        enseignantRepository.deleteById(id);
    }

    private EnseignantResponse toResponse(Enseignant e) {
        return enseignantMapper.toResponse(e);
    }
}
