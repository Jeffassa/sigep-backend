package ci.esatic.sigep.mapper;

import ci.esatic.sigep.dto.response.EmargementResponse;
import ci.esatic.sigep.entity.Emargement;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/** Mapping Emargement → EmargementResponse (aplatit la séance liée et l'enseignant). */
@Mapper(componentModel = "spring")
public interface EmargementMapper {

    @Mapping(target = "seanceId", source = "seance.id")
    @Mapping(target = "matiereLibelle", source = "seance.matiere.libelle")
    @Mapping(target = "classeLibelle", source = "seance.classe.libelle")
    @Mapping(target = "salleLibelle", source = "seance.salle.libelle")
    @Mapping(target = "enseignantNom", source = "enseignant.nom")
    @Mapping(target = "enseignantPrenom", source = "enseignant.prenom")
    EmargementResponse toResponse(Emargement emargement);

    List<EmargementResponse> toResponses(List<Emargement> emargements);
}
