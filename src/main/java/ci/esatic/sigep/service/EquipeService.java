package ci.esatic.sigep.service;

import ci.esatic.sigep.config.ProfilsAdmin;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Équipe d'un établissement : direction, scolarité, comptabilité.
 *
 * <p>Règles partagées par la console et l'API d'agent. Changer les accès d'un membre reste un
 * geste de la console.
 */
@Service
@RequiredArgsConstructor
public class EquipeService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final EtablissementCourantService etablissementCourantService;

    public record Membre(Long id, String email, String profil, boolean actif, boolean principal) { }

    @Transactional(readOnly = true)
    public List<Membre> membres() {
        Etablissement etab = courant();
        return userRepository.findByEtablissementIdOrderByIdAsc(etab.getId()).stream()
                .filter(u -> u.getRoles().stream().noneMatch(r -> r.getName() == ERole.ROLE_ENSEIGNANT))
                .map(u -> new Membre(u.getId(), u.getEmail(), libelle(u), u.isEnabled(), principal(u)))
                .toList();
    }

    /** Vérifie une invitation sans rien créer ni envoyer ; renvoie l'adresse normalisée. */
    @Transactional(readOnly = true)
    public String verifierInvitation(String email, ERole profil) {
        String adresse = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (profil == null || !ProfilsAdmin.LIBELLES.containsKey(profil)
                || !adresse.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new MetierException("INVITATION_INVALIDE", "Indiquez une adresse e-mail valide et un profil.");
        }
        if (userRepository.existsByEmail(adresse)) {
            throw new MetierException("EMAIL_PRIS", "Cette adresse a déjà un compte SIGEP : utilisez-en une autre.");
        }
        return adresse;
    }

    /** Crée le compte avec un mot de passe provisoire, envoyé par e-mail à la personne invitée. */
    @Transactional
    public String inviter(String email, ERole profil) {
        String adresse = verifierInvitation(email, profil);
        Etablissement etab = courant();
        String secret = SecurityUtils.genererMotDePasseProvisoire();
        userRepository.save(User.builder()
                .email(adresse)
                .password(passwordEncoder.encode(secret))
                .mustChangePassword(true)
                .roles(Set.of(roleRepository.findByName(profil).orElseThrow()))
                .etablissement(etab)
                .build());
        mailService.notifierCompteAdministration(etab.getEmailFrom(), adresse, etab.getNomEffectif(),
                ProfilsAdmin.LIBELLES.get(profil), secret);
        return adresse;
    }

    private Etablissement courant() {
        Etablissement e = etablissementCourantService.courant();
        if (e == null) throw new MetierException("INDISPONIBLE", "Aucun établissement rattaché à ce compte.");
        return e;
    }

    private static boolean principal(User u) {
        return u.getRoles().stream().anyMatch(r -> r.getName() == ERole.ROLE_ADMIN);
    }

    private static String libelle(User u) {
        if (principal(u)) return "Administrateur principal";
        return u.getRoles().stream().map(r -> ProfilsAdmin.LIBELLES.get(r.getName()))
                .filter(java.util.Objects::nonNull).findFirst().orElse("—");
    }
}
