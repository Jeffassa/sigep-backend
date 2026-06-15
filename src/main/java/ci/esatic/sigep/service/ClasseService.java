package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.ClasseRequest;
import ci.esatic.sigep.dto.response.ClasseResponse;
import ci.esatic.sigep.entity.Classe;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.ClasseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClasseService {

    private final ClasseRepository classeRepository;

    public List<ClasseResponse> findAll() {
        return classeRepository.findAll().stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    public ClasseResponse getById(Long id) {
        return toResponse(findEntityById(id));
    }

    @Transactional
    public ClasseResponse create(ClasseRequest request) {
        if (classeRepository.existsByCode(request.getCode())) {
            throw new IllegalArgumentException("Code classe deja utilise : " + request.getCode());
        }
        Classe classe = Classe.builder()
                .code(request.getCode())
                .libelle(request.getLibelle())
                .filiere(request.getFiliere())
                .niveau(request.getNiveau())
                .build();
        return toResponse(classeRepository.save(classe));
    }

    @Transactional
    public ClasseResponse update(Long id, ClasseRequest request) {
        Classe existing = findEntityById(id);
        existing.setLibelle(request.getLibelle());
        existing.setFiliere(request.getFiliere());
        existing.setNiveau(request.getNiveau());
        return toResponse(classeRepository.save(existing));
    }

    @Transactional
    public void delete(Long id) {
        if (!classeRepository.existsById(id)) {
            throw new ResourceNotFoundException("Classe", "id", id);
        }
        classeRepository.deleteById(id);
    }

    // Accès entité pour les autres services (ImportService, SeanceService…)
    public Classe findEntityById(Long id) {
        return classeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Classe", "id", id));
    }

    private ClasseResponse toResponse(Classe c) {
        return ClasseResponse.builder()
                .id(c.getId())
                .code(c.getCode())
                .libelle(c.getLibelle())
                .filiere(c.getFiliere())
                .niveau(c.getNiveau())
                .build();
    }
}
