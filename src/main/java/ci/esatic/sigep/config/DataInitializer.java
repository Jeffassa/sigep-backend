package ci.esatic.sigep.config;

import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

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
        String adminEmail = "admin@esatic.ci";
        if (userRepository.existsByEmail(adminEmail)) return;

        Role adminRole = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseThrow();

        User admin = User.builder()
                .email(adminEmail)
                .password(passwordEncoder.encode("Admin@2026"))
                .roles(Set.of(adminRole))
                .build();

        userRepository.save(admin);
        log.info("Admin créé → email: {}", adminEmail);
    }
}
