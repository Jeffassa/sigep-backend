package ci.esatic.sigep.mapper;

import ci.esatic.sigep.dto.response.SalleResponse;
import ci.esatic.sigep.entity.Salle;
import org.mapstruct.Mapper;

import java.util.List;

/** Mapping Salle → SalleResponse (champs de même nom, généré par MapStruct). */
@Mapper(componentModel = "spring")
public interface SalleMapper {
    SalleResponse toResponse(Salle salle);
    List<SalleResponse> toResponses(List<Salle> salles);
}
