package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Un vacataire qui enseigne dans plusieurs établissements garde une seule adresse et un seul mot
 * de passe. Le compte reste le sien : un établissement qui le rattache n'en reçoit jamais le
 * secret et n'obtient que sa propre fiche enseignant.
 */
@Service
@RequiredArgsConstructor
public class CompteEnseignantService {

    public record Fiche(Long etablissementId, StatutEnseignant statut, String nom, String prenom) {}

    private final UserRepository userRepository;
    private final EnseignantRepository enseignantRepository;

    public List<Fiche> fiches(Long userId) {
        return enseignantRepository.fichesDuCompte(userId).stream()
                .map(r -> new Fiche(r[0] == null ? null : ((Number) r[0]).longValue(),
                        r[1] == null ? null : StatutEnseignant.valueOf((String) r[1]),
                        (String) r[2], (String) r[3]))
                .toList();
    }

    /**
     * Compte existant à rattacher à cet établissement, s'il y en a un.
     *
     * @throws IllegalArgumentException si l'adresse ne peut pas servir à une fiche enseignant ici
     */
    public Optional<User> compteARattacher(String email, Long etablissementId) {
        Optional<User> compte = userRepository.findByEmail(email);
        if (compte.isEmpty()) return compte;
        boolean enseignantSeul = compte.get().getRoles().stream()
                .allMatch(r -> r.getName() == ERole.ROLE_ENSEIGNANT);
        if (!enseignantSeul) {
            throw new IllegalArgumentException("cette adresse appartient à un compte d'administration");
        }
        boolean dejaIci = fiches(compte.get().getId()).stream()
                .anyMatch(f -> Objects.equals(f.etablissementId(), etablissementId));
        if (dejaIci) {
            throw new IllegalArgumentException("cet enseignant a déjà une fiche dans votre établissement");
        }
        return compte;
    }

    /** La fiche d'un compte vient d'être supprimée : le compte disparaît s'il ne sert plus ailleurs. */
    public void retirerSiPlusUtilise(User compte) {
        if (compte != null && fiches(compte.getId()).isEmpty()) {
            userRepository.delete(compte);
        }
    }
}
