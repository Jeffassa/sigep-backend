package ci.esatic.sigep.mapper;

import ci.esatic.sigep.dto.response.RattrapageResponse;
import ci.esatic.sigep.entity.DemandeRattrapage;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/** Mapping DemandeRattrapage → RattrapageResponse (aplatit enseignant/matière/classe + séance créée). */
@Mapper(componentModel = "spring")
public interface RattrapageMapper {

    @Mapping(target = "enseignantNom", source = "enseignant.nom")
    @Mapping(target = "enseignantPrenom", source = "enseignant.prenom")
    @Mapping(target = "matiereLibelle", source = "matiere.libelle")
    @Mapping(target = "classeLibelle", source = "classe.libelle")
    @Mapping(target = "seanceRattrapageId", source = "seanceRattrapage.id")
    RattrapageResponse toResponse(DemandeRattrapage demande);

    List<RattrapageResponse> toResponses(List<DemandeRattrapage> demandes);
}
