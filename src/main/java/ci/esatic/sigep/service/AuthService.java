package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.LoginRequest;
import ci.esatic.sigep.dto.request.RegisterEnseignantRequest;
import ci.esatic.sigep.dto.response.AuthResponse;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.exception.CompteNonValideException;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final EnseignantRepository enseignantRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = (User) authentication.getPrincipal();

        // Un enseignant ne peut se connecter que si l'administration a validé son compte.
        // (Les comptes administrateurs n'ont pas de profil enseignant : ils ne sont pas concernés.)
        var enseignantOpt = enseignantRepository.findByUserId(user.getId());
        enseignantOpt.ifPresent(ens -> {
            if (ens.getStatut() == StatutEnseignant.PENDING) {
                throw new CompteNonValideException(
                        "Votre compte est en attente de validation par l'administration.");
            }
            if (ens.getStatut() == StatutEnseignant.REJECTED) {
                throw new CompteNonValideException(
                        "Votre compte a été refusé. Veuillez contacter l'administration.");
            }
        });

        String token = jwtService.generateToken(user);

        List<String> roles = user.getRoles().stream()
                .map(role -> role.getName().name())
                .collect(Collectors.toList());

        // Récupérer nom/prenom si enseignant
        String nom = "";
        String prenom = "";
        if (enseignantOpt.isPresent()) {
            nom = enseignantOpt.get().getNom();
            prenom = enseignantOpt.get().getPrenom();
        }

        return AuthResponse.builder()
                .token(token)
                .type("Bearer")
                .id(user.getId())
                .email(user.getEmail())
                .roles(roles)
                .nom(nom)
                .prenom(prenom)
                .build();
    }

    @Transactional
    public AuthResponse registerEnseignant(RegisterEnseignantRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email déjà utilisé : " + request.getEmail());
        }
        if (enseignantRepository.existsByMatricule(request.getMatricule())) {
            throw new IllegalArgumentException("Matricule déjà utilisé : " + request.getMatricule());
        }

        Role role = roleRepository.findByName(ERole.ROLE_ENSEIGNANT)
                .orElseThrow(() -> new RuntimeException("Rôle ENSEIGNANT non trouvé en base"));

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .roles(Set.of(role))
                .build();
        user = userRepository.save(user);

        Enseignant enseignant = Enseignant.builder()
                .matricule(request.getMatricule())
                .nom(request.getNom())
                .prenom(request.getPrenom())
                .departement(request.getDepartement())
                .grade(request.getGrade())
                .statut(StatutEnseignant.PENDING)
                .user(user)
                .build();
        enseignantRepository.save(enseignant);

        // Aucun token n'est délivré : le compte est créé en attente (PENDING) et ne
        // pourra se connecter qu'après validation explicite par l'administration.
        return AuthResponse.builder()
                .token(null)
                .type("Bearer")
                .id(user.getId())
                .email(user.getEmail())
                .roles(List.of(ERole.ROLE_ENSEIGNANT.name()))
                .nom(enseignant.getNom())
                .prenom(enseignant.getPrenom())
                .build();
    }
}
