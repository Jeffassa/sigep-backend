package ci.esatic.sigep.annuaire;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.StatutReconnaissance;
import ci.esatic.sigep.repository.EtablissementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Rapproche les établissements clients des listes officielles de l'État. Un nom qui figure sans
 * ambiguïté sur une liste vaut reconnaissance ; sinon l'établissement reste à vérifier, et la
 * plateforme tranche au vu de son autorisation. Une décision de la plateforme n'est jamais
 * écrasée par un rapprochement automatique.
 */
@Service
public class ReconnaissanceService {

    private final EtablissementOfficielRepository officiels;
    private final EtablissementRepository etablissements;

    public ReconnaissanceService(EtablissementOfficielRepository officiels, EtablissementRepository etablissements) {
        this.officiels = officiels;
        this.etablissements = etablissements;
    }

    /** Pose le statut de l'établissement d'après les listes, sans l'enregistrer. */
    public void rapprocher(Etablissement e) {
        if (e.getReconnaissanceAuteur() != null) return;   // décision de la plateforme
        List<EtablissementOfficiel> trouves = officiels.findByNomCle(TexteRecherche.cle(e.getNom()));
        if (trouves.size() == 1) {
            EtablissementOfficiel o = trouves.get(0);
            e.setReconnaissance(StatutReconnaissance.RECONNU);
            e.setOfficielSource(o.getSource().name());
            e.setOfficielCode(o.getCodeOfficiel());
            e.setOfficielNom(o.getNom());
        } else {
            // Aucun nom, ou plusieurs établissements du même nom : la plateforme tranche.
            e.setReconnaissance(StatutReconnaissance.A_VERIFIER);
            e.setOfficielSource(null);
            e.setOfficielCode(null);
            e.setOfficielNom(null);
        }
    }

    /** Après un import : refait le rapprochement de tous les établissements sans décision manuelle. */
    @Transactional
    public int rapprocherTous() {
        int reconnus = 0;
        for (Etablissement e : etablissements.findAll()) {
            if (e.getReconnaissanceAuteur() != null) continue;
            rapprocher(e);
            if (e.getReconnaissance() == StatutReconnaissance.RECONNU) reconnus++;
        }
        return reconnus;
    }

    /** Décision de la plateforme, au vu d'un document : vérifié ou non reconnu, constat obligatoire. */
    @Transactional
    public Etablissement decider(Long id, StatutReconnaissance decision, String constat, String auteur) {
        if (decision != StatutReconnaissance.VERIFIE && decision != StatutReconnaissance.NON_RECONNU) {
            throw new IllegalArgumentException("Décision inconnue.");
        }
        String c = constat == null ? "" : constat.strip();
        if (c.length() < 5) {
            throw new IllegalArgumentException("Le constat est obligatoire : le document examiné et ce qu'il établit.");
        }
        if (c.length() > 500) c = c.substring(0, 500);
        Etablissement e = etablissements.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Établissement introuvable."));
        e.setReconnaissance(decision);
        e.setReconnaissanceAuteur(auteur);
        e.setReconnaissanceDate(LocalDateTime.now());
        e.setReconnaissanceConstat(c);
        return etablissements.save(e);
    }
}
