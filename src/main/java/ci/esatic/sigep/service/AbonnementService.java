package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Cycle de vie de l'abonnement d'un établissement : expiration, rappels, prolongation.
 * {@code dateExpiration == null} signifie « pas d'expiration » (Free gratuit, ou tenant
 * illimité comme l'établissement par défaut) : jamais bloqué.
 */
@Service
public class AbonnementService {

    /** On commence à rappeler quand il reste ce nombre de jours (ou moins). */
    public static final long SEUIL_RAPPEL_JOURS = 7;

    /** Abonnement expiré ? (date d'expiration définie et passée). */
    public boolean estExpire(Etablissement e) {
        return e != null && e.getDateExpiration() != null
                && e.getDateExpiration().isBefore(LocalDate.now());
    }

    /** Jours restants avant expiration (négatif si déjà expiré), ou null si pas d'expiration. */
    public Long joursAvantExpiration(Etablissement e) {
        if (e == null || e.getDateExpiration() == null) return null;
        return ChronoUnit.DAYS.between(LocalDate.now(), e.getDateExpiration());
    }

    /** Faut-il afficher un rappel ? (expiré, ou expiration dans ≤ SEUIL_RAPPEL_JOURS jours). */
    public boolean doitRappeler(Etablissement e) {
        Long j = joursAvantExpiration(e);
        return j != null && j <= SEUIL_RAPPEL_JOURS;
    }

    /** Prolonge l'abonnement de {@code mois} mois (à partir d'aujourd'hui si déjà expiré). */
    public void prolonger(Etablissement e, int mois) {
        LocalDate base = (e.getDateExpiration() == null || e.getDateExpiration().isBefore(LocalDate.now()))
                ? LocalDate.now() : e.getDateExpiration();
        e.setDateExpiration(base.plusMonths(mois));
    }
}
