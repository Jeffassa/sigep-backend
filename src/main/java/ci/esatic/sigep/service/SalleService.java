package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.SalleRequest;
import ci.esatic.sigep.dto.response.SalleResponse;
import ci.esatic.sigep.entity.Salle;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.SalleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SalleService {

    private final SalleRepository salleRepository;

    public List<SalleResponse> findAll() {
        return salleRepository.findAll().stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    public SalleResponse getById(Long id) {
        return toResponse(findEntityById(id));
    }

    // Retourne l'entité — utilisé par RattrapageController et ImportService
    public Salle findById(Long id) {
        return findEntityById(id);
    }

    public Salle findByCode(String code) {
        return salleRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Salle", "code", code));
    }

    @Transactional
    public SalleResponse create(SalleRequest request) {
        if (salleRepository.existsByCode(request.getCode())) {
            throw new IllegalArgumentException("Code salle deja utilise : " + request.getCode());
        }
        Salle salle = Salle.builder()
                .code(request.getCode())
                .batiment(request.getBatiment())
                .capacite(request.getCapacite())
                .build();
        return toResponse(salleRepository.save(salle));
    }

    @Transactional
    public SalleResponse update(Long id, SalleRequest request) {
        Salle existing = findEntityById(id);
        existing.setBatiment(request.getBatiment());
        existing.setCapacite(request.getCapacite());
        return toResponse(salleRepository.save(existing));
    }

    @Transactional
    public void delete(Long id) {
        if (!salleRepository.existsById(id)) {
            throw new ResourceNotFoundException("Salle", "id", id);
        }
        salleRepository.deleteById(id);
    }

    private Salle findEntityById(Long id) {
        return salleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Salle", "id", id));
    }

    private SalleResponse toResponse(Salle s) {
        return SalleResponse.builder()
                .id(s.getId())
                .code(s.getCode())
                .batiment(s.getBatiment())
                .capacite(s.getCapacite())
                .build();
    }
}
