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
 * ambiguïté sur une liste, et dont la fiche n'est pas déjà tenue par un autre client, vaut
 * reconnaissance du nom (pas de l'identité de celui qui s'inscrit) ; sinon l'établissement reste à vérifier, et la
 * plateforme tranche au vu de son autorisation. Une décision de la plateforme n'est jamais
 * écrasée par un rapprochement automatique.
 */
@Service
public class ReconnaissanceService {

    /** Statuts qui « tiennent » une fiche officielle. */
    private static final List<StatutReconnaissance> TENANTS =
            List.of(StatutReconnaissance.RECONNU, StatutReconnaissance.VERIFIE);

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
            e.setOfficielSource(o.getSource().name());
            e.setOfficielCode(o.getCodeOfficiel());
            e.setOfficielNom(o.getNom());
            // Une fiche officielle ne reconnaît qu'un client : le second qui la réclame est à vérifier.
            boolean dejaPrise = etablissements.existsByOfficielSourceAndOfficielNomAndReconnaissanceInAndIdNot(
                    o.getSource().name(), o.getNom(), TENANTS, e.getId() == null ? -1L : e.getId());
            e.setReconnaissance(dejaPrise ? StatutReconnaissance.A_VERIFIER : StatutReconnaissance.RECONNU);
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
        // Par ordre d'inscription : le premier client garde la fiche officielle.
        for (Etablissement e : etablissements.findAll(org.springframework.data.domain.Sort.by("id"))) {
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
