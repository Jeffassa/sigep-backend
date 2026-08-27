package ci.esatic.sigep.mapper;

import ci.esatic.sigep.dto.response.MatiereResponse;
import ci.esatic.sigep.entity.Matiere;
import org.mapstruct.Mapper;

import java.util.List;

/** Mapping Matiere → MatiereResponse (champs de même nom). */
@Mapper(componentModel = "spring")
public interface MatiereMapper {
    MatiereResponse toResponse(Matiere matiere);
    List<MatiereResponse> toResponses(List<Matiere> matieres);
}
