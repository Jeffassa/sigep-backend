package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EtablissementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Établissement du compte connecté, RELU en base à chaque appel.
 * Le principal stocké en session est un instantané figé à la connexion : sans relecture,
 * un changement récent (paiement, changement de plan, prolongation) ne serait visible
 * qu'après reconnexion. On relit donc l'établissement pour que l'accès, l'affichage du plan
 * et les verrous premium reflètent l'état réel immédiatement.
 */
@Service
@RequiredArgsConstructor
public class EtablissementCourantService {

    private final EtablissementRepository etablissementRepository;

    /** Établissement frais du compte connecté, ou null (hors session admin / compte sans tenant). */
    public Etablissement courant() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof User u) || u.getEtablissement() == null) {
            return null;
        }
        return etablissementRepository.findById(u.getEtablissement().getId())
                .orElse(u.getEtablissement());
    }
}
