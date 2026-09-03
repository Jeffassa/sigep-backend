package ci.esatic.sigep.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppression RGPD d'un établissement : efface l'établissement ET toutes ses données
 * (utilisateurs, enseignants, séances, émargements, référentiels, rapports, jetons).
 * Réservé au super admin. Requêtes natives exécutées dans l'ordre des clés étrangères,
 * en une seule transaction — irréversible.
 */
@Service
public class SuppressionEtablissementService {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void supprimerToutesLesDonnees(Long etablissementId) {
        // 1) Enfants (référencent séances et/ou enseignants)
        exec("DELETE FROM emargements WHERE etablissement_id = :id", etablissementId);
        exec("DELETE FROM demandes_rattrapage WHERE etablissement_id = :id", etablissementId);
        exec("DELETE FROM rapports_pdf WHERE etablissement_id = :id", etablissementId);
        // 2) Séances (référencent enseignants, matières, classes, salles)
        exec("DELETE FROM seances WHERE etablissement_id = :id", etablissementId);
        // 3) Enseignants (référencent les utilisateurs)
        exec("DELETE FROM enseignants WHERE etablissement_id = :id", etablissementId);
        // 4) Référentiels
        exec("DELETE FROM classes WHERE etablissement_id = :id", etablissementId);
        exec("DELETE FROM matieres WHERE etablissement_id = :id", etablissementId);
        exec("DELETE FROM salles WHERE etablissement_id = :id", etablissementId);
        // 5) Paiements (trace comptable) + intentions de paiement Mobile Money en cours.
        //    Les intentions portent le numéro de téléphone du payeur : donnée personnelle
        //    à effacer au même titre que le reste (RGPD).
        exec("DELETE FROM paiement_intents WHERE etablissement_id = :id", etablissementId);
        exec("DELETE FROM paiements WHERE etablissement_id = :id", etablissementId);
        // 6) Sessions + rôles des utilisateurs, puis les utilisateurs eux-mêmes
        exec("DELETE FROM refresh_tokens WHERE user_id IN "
                + "(SELECT id FROM users WHERE etablissement_id = :id)", etablissementId);
        exec("DELETE FROM user_roles WHERE user_id IN "
                + "(SELECT id FROM users WHERE etablissement_id = :id)", etablissementId);
        exec("DELETE FROM users WHERE etablissement_id = :id", etablissementId);
        // 6) L'établissement
        exec("DELETE FROM etablissements WHERE id = :id", etablissementId);
    }

    private void exec(String sql, Long id) {
        em.createNativeQuery(sql).setParameter("id", id).executeUpdate();
    }
}
