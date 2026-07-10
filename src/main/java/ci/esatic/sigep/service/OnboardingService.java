package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.InscriptionEtablissementRequest;
import ci.esatic.sigep.dto.response.AuthResponse;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Plan;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.entity.StatutEtablissement;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Set;

/**
 * Onboarding SaaS : un nouvel établissement s'inscrit (self-service). On crée le tenant
 * (plan FREE, statut EN_ATTENTE) et son premier administrateur. SA-2 : AUCUN token n'est
 * délivré et la connexion est bloquée tant que le SUPER ADMIN n'a pas validé le dossier ;
 * un e-mail « dossier en cours d'analyse » est envoyé à l'inscription.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final EtablissementRepository etablissementRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;

    @Transactional
    public AuthResponse inscrire(InscriptionEtablissementRequest req) {
        if (userRepository.existsByEmail(req.getAdminEmail())) {
            throw new IllegalArgumentException("Email déjà utilisé : " + req.getAdminEmail());
        }

        Etablissement etablissement = etablissementRepository.save(Etablissement.builder()
                .nom(req.getNomEtablissement().trim())
                .slug(slugUnique(req.getNomEtablissement()))
                .plan(Plan.FREE)
                .maxEnseignants(10)
                .statut(StatutEtablissement.EN_ATTENTE)   // validation super admin requise
                .actif(true)
                .build());

        Role adminRole = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseThrow(() -> new RuntimeException("Rôle ADMIN non trouvé en base"));

        User admin = userRepository.save(User.builder()
                .email(req.getAdminEmail())
                .password(passwordEncoder.encode(req.getAdminPassword()))
                .roles(Set.of(adminRole))
                .etablissement(etablissement)   // rattachement au nouveau tenant
                .build());

        mailService.notifierInscriptionEtablissementRecue(
                admin.getEmail(), req.getAdminPrenom(), etablissement.getNom());

        // Pas de token : le compte ne devient utilisable qu'après validation du dossier.
        return AuthResponse.builder()
                .id(admin.getId())
                .email(admin.getEmail())
                .roles(List.of(ERole.ROLE_ADMIN.name()))
                .nom(req.getAdminNom())
                .prenom(req.getAdminPrenom())
                .build();
    }

    /** Slug unique à partir du nom (suffixe numérique en cas de collision). */
    private String slugUnique(String nom) {
        String base = slugify(nom);
        if (base.isBlank()) base = "etablissement";
        String slug = base;
        int i = 2;
        while (etablissementRepository.findBySlug(slug).isPresent()) {
            slug = base + "-" + i++;
        }
        return slug;
    }

    private String slugify(String s) {
        String sansAccents = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return sansAccents.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
