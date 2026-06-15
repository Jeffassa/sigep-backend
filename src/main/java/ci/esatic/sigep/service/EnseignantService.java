package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.response.EnseignantResponse;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.EnseignantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EnseignantService {

    private final EnseignantRepository enseignantRepository;

    public Page<EnseignantResponse> searchEnseignants(String search, String departement,
                                                       StatutEnseignant statut, Pageable pageable) {
        Page<Enseignant> page = statut == null
                ? enseignantRepository.searchEnseignants(search, departement, pageable)
                : enseignantRepository.searchEnseignantsByStatut(search, departement, statut.name(), pageable);
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
        return toResponse(enseignantRepository.save(enseignant));
    }

    @Transactional
    public void delete(Long id) {
        if (!enseignantRepository.existsById(id)) {
            throw new ResourceNotFoundException("Enseignant", "id", id);
        }
        enseignantRepository.deleteById(id);
    }

    private EnseignantResponse toResponse(Enseignant e) {
        return EnseignantResponse.builder()
                .id(e.getId())
                .matricule(e.getMatricule())
                .nom(e.getNom())
                .prenom(e.getPrenom())
                .email(e.getUser() != null ? e.getUser().getEmail() : null)
                .departement(e.getDepartement())
                .grade(e.getGrade())
                .statut(e.getStatut())
                .photoUrl(e.getPhotoUrl())
                .build();
    }
}
