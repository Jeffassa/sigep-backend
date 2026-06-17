package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.MatiereRequest;
import ci.esatic.sigep.dto.response.MatiereResponse;
import ci.esatic.sigep.entity.Matiere;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.MatiereRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MatiereService {

    private final MatiereRepository matiereRepository;

    public List<MatiereResponse> findAll() {
        return matiereRepository.findAll().stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    public MatiereResponse getById(Long id) {
        return toResponse(findEntityById(id));
    }

    @Transactional
    public MatiereResponse create(MatiereRequest request) {
        if (matiereRepository.existsByLibelleIgnoreCase(request.getLibelle())) {
            throw new IllegalArgumentException("Matiere deja utilisee : " + request.getLibelle());
        }
        Matiere matiere = Matiere.builder()
                .libelle(request.getLibelle())
                .description(request.getDescription())
                .build();
        return toResponse(matiereRepository.save(matiere));
    }

    @Transactional
    public MatiereResponse update(Long id, MatiereRequest request) {
        Matiere existing = findEntityById(id);
        existing.setLibelle(request.getLibelle());
        existing.setDescription(request.getDescription());
        return toResponse(matiereRepository.save(existing));
    }

    @Transactional
    public void delete(Long id) {
        if (!matiereRepository.existsById(id)) {
            throw new ResourceNotFoundException("Matiere", "id", id);
        }
        matiereRepository.deleteById(id);
    }

    // Accès entité pour les autres services (ImportService, SeanceService…)
    public Matiere findEntityById(Long id) {
        return matiereRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Matiere", "id", id));
    }

    private MatiereResponse toResponse(Matiere m) {
        return MatiereResponse.builder()
                .id(m.getId())
                .libelle(m.getLibelle())
                .description(m.getDescription())
                .build();
    }
}
