package ci.esatic.sigep.mapper;

import ci.esatic.sigep.dto.response.EnseignantResponse;
import ci.esatic.sigep.entity.Enseignant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Mapping Enseignant → EnseignantResponse (e-mail issu du compte utilisateur lié). */
@Mapper(componentModel = "spring")
public interface EnseignantMapper {

    @Mapping(target = "email", source = "user.email")
    EnseignantResponse toResponse(Enseignant enseignant);
}
