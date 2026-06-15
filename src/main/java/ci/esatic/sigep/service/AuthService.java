package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.LoginRequest;
import ci.esatic.sigep.dto.request.RegisterEnseignantRequest;
import ci.esatic.sigep.dto.response.AuthResponse;
import ci.esatic.sigep.entity.*;
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
        String token = jwtService.generateToken(user);

        List<String> roles = user.getRoles().stream()
                .map(role -> role.getName().name())
                .collect(Collectors.toList());

        // Récupérer nom/prenom si enseignant
        String nom = "";
        String prenom = "";
        var enseignantOpt = enseignantRepository.findByUserId(user.getId());
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

        String token = jwtService.generateToken(user);

        return AuthResponse.builder()
                .token(token)
                .type("Bearer")
                .id(user.getId())
                .email(user.getEmail())
                .roles(List.of(ERole.ROLE_ENSEIGNANT.name()))
                .nom(enseignant.getNom())
                .prenom(enseignant.getPrenom())
                .build();
    }
}
