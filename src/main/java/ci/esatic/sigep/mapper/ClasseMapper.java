package ci.esatic.sigep.mapper;

import ci.esatic.sigep.dto.response.ClasseResponse;
import ci.esatic.sigep.entity.Classe;
import org.mapstruct.Mapper;

import java.util.List;

/** Mapping Classe → ClasseResponse (champs de même nom). */
@Mapper(componentModel = "spring")
public interface ClasseMapper {
    ClasseResponse toResponse(Classe classe);
    List<ClasseResponse> toResponses(List<Classe> classes);
}
