package ci.esatic.sigep.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Marque d'un appel fait par un agent, portée dans les « détails » de l'authentification.
 *
 * <p>Le principal reste l'administrateur dont l'agent emprunte les droits : l'isolation par
 * établissement, les profils et l'établissement courant s'appliquent donc à l'agent exactement
 * comme à lui. Cette marque sert à savoir, en plus, que c'est l'agent qui agit.
 *
 * @param cleId   clé utilisée, pour le journal
 * @param libelle nom donné à l'agent
 */
public record AgentIdentite(Long cleId, String libelle) {

    /** L'appel en cours vient-il d'un agent ? */
    public static boolean appelEnCours() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getDetails() instanceof AgentIdentite;
    }

    @Override
    public String toString() {
        return "agent:" + cleId;
    }
}
