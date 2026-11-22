package ci.esatic.sigep.annuaire;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** Recherche d'un établissement dans l'annuaire, pour la saisie assistée de l'inscription. */
@Service
public class AnnuaireService {

    public static final int MAX_RESULTATS = 8;
    private static final int CANDIDATS = 300;

    private final AnnuaireEtablissementRepository etablissements;

    public AnnuaireService(AnnuaireEtablissementRepository etablissements) {
        this.etablissements = etablissements;
    }

    public record Suggestion(String nom, String ville, String pays, String paysNom, String type) {
    }

    /**
     * Les établissements dont le nom contient tous les mots tapés (sans tenir compte des accents),
     * universités et écoles supérieures d'abord, puis les noms qui commencent par la recherche.
     */
    @Transactional(readOnly = true)
    public List<Suggestion> rechercher(String recherche, String paysCode) {
        List<String> mots = TexteRecherche.mots(recherche);
        if (mots.isEmpty() || mots.get(0).length() < 2) return List.of();
        String pays = PaysUemoa.deCode(paysCode).filter(PaysUemoa::isOuvert)
                .orElse(PaysUemoa.CI).getCode();
        String debut = TexteRecherche.normaliser(recherche);

        return etablissements.contenant(mots.get(0), pays, PageRequest.of(0, CANDIDATS)).stream()
                .filter(a -> mots.stream().allMatch(m -> a.getNomRecherche().contains(m)))
                .sorted(Comparator
                        .comparing((AnnuaireEtablissement a) -> !a.getNomRecherche().startsWith(debut))
                        .thenComparing(a -> a.getType().ordinal())
                        .thenComparing(a -> a.getNom().length()))
                .limit(MAX_RESULTATS)
                .map(a -> new Suggestion(a.getNom(), a.getVille(), a.getPaysCode(),
                        PaysUemoa.deCode(a.getPaysCode()).map(PaysUemoa::getNom).orElse(a.getPaysCode()),
                        a.getType().getLibelle()))
                .toList();
    }
}
