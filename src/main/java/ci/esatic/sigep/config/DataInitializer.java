package ci.esatic.sigep.config;

import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EtablissementRepository;
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
    private final EtablissementRepository etablissementRepository;
    private final PasswordEncoder passwordEncoder;

    /** Slug du tenant par défaut (rattache les données du mode mono-établissement actuel). */
    private static final String SLUG_DEFAUT = "default";

    @Value("${app.admin.email:admin@esatic.ci}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        initRoles();
        Etablissement tenantParDefaut = initEtablissementParDefaut();
        initAdminUser(tenantParDefaut);
    }

    /**
     * Tenant par défaut : rattache les données existantes (mode mono-établissement actuel).
     * En prod il est aussi créé par la migration Flyway V7 ; ici on le retrouve (ou on le
     * crée pour les tests sur H2 où Flyway est désactivé).
     */
    private Etablissement initEtablissementParDefaut() {
        return etablissementRepository.findBySlug(SLUG_DEFAUT).orElseGet(() -> {
            Etablissement e = Etablissement.builder()
                    .nom("Établissement par défaut")
                    .slug(SLUG_DEFAUT)
                    .plan(Plan.ENTERPRISE)   // établissement « propriétaire » : accès complet
                    .maxEnseignants(0)        // 0 = illimité
                    .build();
            e = etablissementRepository.save(e);
            log.info("Établissement par défaut créé (slug={})", SLUG_DEFAUT);
            return e;
        });
    }

    private void initRoles() {
        for (ERole eRole : ERole.values()) {
            if (roleRepository.findByName(eRole).isEmpty()) {
                roleRepository.save(new Role(null, eRole));
                log.info("Role créé : {}", eRole);
            }
        }
    }

    private void initAdminUser(Etablissement tenant) {
        // Admin déjà présent : on s'assure seulement qu'il est rattaché à un établissement.
        var existant = userRepository.findByEmail(adminEmail).orElse(null);
        if (existant != null) {
            if (existant.getEtablissement() == null) {
                existant.setEtablissement(tenant);
                userRepository.save(existant);
                log.info("Admin existant rattaché à l'établissement par défaut.");
            }
            return;
        }

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
                .etablissement(tenant)
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
