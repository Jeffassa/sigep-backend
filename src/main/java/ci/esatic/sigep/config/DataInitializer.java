package ci.esatic.sigep.config;

import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:admin@esatic.ci}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        initRoles();
        initAdminUser();
    }

    private void initRoles() {
        for (ERole eRole : ERole.values()) {
            if (roleRepository.findByName(eRole).isEmpty()) {
                roleRepository.save(new Role(null, eRole));
                log.info("Role créé : {}", eRole);
            }
        }
    }

    private void initAdminUser() {
        if (userRepository.existsByEmail(adminEmail)) return;

        Role adminRole = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseThrow();

        // Si aucun mot de passe n'est fourni, on en génère un aléatoire et on l'affiche
        // UNE seule fois dans les logs (l'admin doit le changer ensuite).
        boolean genere = adminPassword == null || adminPassword.isBlank();
        String motDePasse = genere ? genererMotDePasseAleatoire() : adminPassword;

        User admin = User.builder()
                .email(adminEmail)
                .password(passwordEncoder.encode(motDePasse))
                .roles(Set.of(adminRole))
                .build();

        userRepository.save(admin);

        if (genere) {
            log.warn("====================================================================");
            log.warn("  COMPTE ADMIN CREE — mot de passe genere aleatoirement :");
            log.warn("  email    : {}", adminEmail);
            log.warn("  password : {}", motDePasse);
            log.warn("  >> Notez-le et changez-le. Definissez ADMIN_PASSWORD pour le fixer.");
            log.warn("====================================================================");
        } else {
            log.info("Admin créé → email: {}", adminEmail);
        }
    }

    private String genererMotDePasseAleatoire() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
