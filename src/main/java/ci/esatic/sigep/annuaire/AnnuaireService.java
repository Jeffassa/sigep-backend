package ci.esatic.sigep.annuaire;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recherche d'un établissement pour la saisie assistée de l'inscription : d'abord dans les listes
 * officielles de l'État, puis dans l'annuaire collecté depuis OpenStreetMap.
 */
@Service
public class AnnuaireService {

    public static final int MAX_RESULTATS = 8;
    private static final int CANDIDATS = 300;

    private final AnnuaireEtablissementRepository etablissements;
    private final EtablissementOfficielRepository officiels;

    public AnnuaireService(AnnuaireEtablissementRepository etablissements, EtablissementOfficielRepository officiels) {
        this.etablissements = etablissements;
        this.officiels = officiels;
    }

    public record Suggestion(String nom, String ville, String pays, String paysNom, String type) {
    }

    /** Un candidat, d'où qu'il vienne : ce qu'il faut pour le trier puis le proposer. */
    private record Candidat(String nomRecherche, boolean officiel, int rang, Suggestion suggestion) {
    }

    /**
     * Les établissements dont le nom contient tous les mots tapés (sans tenir compte des accents) :
     * les noms qui commencent par la recherche d'abord, puis ceux des listes officielles, puis les
     * universités et écoles supérieures. Un nom déjà proposé par une liste officielle n'est pas
     * repris depuis OpenStreetMap.
     */
    @Transactional(readOnly = true)
    public List<Suggestion> rechercher(String recherche, String paysCode) {
        List<String> mots = TexteRecherche.mots(recherche);
        if (mots.isEmpty() || mots.get(0).length() < 2) return List.of();
        PaysUemoa pays = PaysUemoa.deCode(paysCode).filter(PaysUemoa::isOuvert).orElse(PaysUemoa.CI);
        String debut = TexteRecherche.normaliser(recherche);

        List<Candidat> candidats = new ArrayList<>();
        Set<String> clesOfficielles = new HashSet<>();
        if (pays == PaysUemoa.CI) {   // les listes officielles sont celles de la Côte d'Ivoire
            for (EtablissementOfficiel o : officiels.contenant(mots.get(0), PageRequest.of(0, CANDIDATS))) {
                clesOfficielles.add(o.getNomCle());
                candidats.add(new Candidat(o.getNomRecherche(), true, o.getCategorie().getRang(),
                        new Suggestion(o.getNom(), o.getVille() != null ? o.getVille() : o.getCommune(),
                                pays.getCode(), pays.getNom(), o.getCategorie().getLibelle())));
            }
        }
        for (AnnuaireEtablissement a : etablissements.contenant(mots.get(0), pays.getCode(), PageRequest.of(0, CANDIDATS))) {
            if (clesOfficielles.contains(TexteRecherche.cle(a.getNom()))) continue;
            candidats.add(new Candidat(a.getNomRecherche(), false, a.getType().ordinal(),
                    new Suggestion(a.getNom(), a.getVille(), a.getPaysCode(),
                            PaysUemoa.deCode(a.getPaysCode()).map(PaysUemoa::getNom).orElse(a.getPaysCode()),
                            a.getType().getLibelle())));
        }

        return candidats.stream()
                .filter(c -> mots.stream().allMatch(m -> c.nomRecherche().contains(m)))
                .sorted(Comparator
                        .comparing((Candidat c) -> !c.nomRecherche().startsWith(debut))
                        .thenComparing(c -> !c.officiel())
                        .thenComparingInt(Candidat::rang)
                        .thenComparingInt(c -> c.suggestion().nom().length()))
                .limit(MAX_RESULTATS)
                .map(Candidat::suggestion)
                .toList();
    }
}
