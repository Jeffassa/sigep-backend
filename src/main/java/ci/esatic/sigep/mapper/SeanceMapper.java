package ci.esatic.sigep.mapper;

import ci.esatic.sigep.dto.response.SeanceResponse;
import ci.esatic.sigep.entity.Seance;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/** Mapping Seance → SeanceResponse (aplatit matière/classe/salle/enseignant). */
@Mapper(componentModel = "spring")
public interface SeanceMapper {

    @Mapping(target = "matiereLibelle", source = "matiere.libelle")
    @Mapping(target = "classeLibelle", source = "classe.libelle")
    @Mapping(target = "salleLibelle", source = "salle.libelle")
    @Mapping(target = "salleBatiment", source = "salle.batiment")
    @Mapping(target = "enseignantNom", source = "enseignant.nom")
    @Mapping(target = "enseignantPrenom", source = "enseignant.prenom")
    SeanceResponse toResponse(Seance seance);

    List<SeanceResponse> toResponses(List<Seance> seances);
}
