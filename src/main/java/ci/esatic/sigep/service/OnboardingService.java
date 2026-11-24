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
    private final ci.esatic.sigep.annuaire.ReconnaissanceService reconnaissanceService;

    /**
     * C5 : auto-validation des inscriptions. DÉSACTIVÉ par défaut (comportement actuel :
     * validation manuelle par le super-admin). Si {@code true}, le tenant naît VALIDE et
     * son espace est immédiatement utilisable (revue anti-fraude a posteriori).
     */
    @org.springframework.beans.factory.annotation.Value("${app.onboarding.auto-validation:false}")
    private boolean autoValidation;

    /** Quota d'enseignants du plan FREE (source unique — M1). */
    @org.springframework.beans.factory.annotation.Value("${app.plans.free-max-enseignants:10}")
    private int freeMaxEnseignants;

    /** Ce que crée une inscription : l'établissement et son premier administrateur. */
    public record Inscription(Etablissement etablissement, User admin, AuthResponse reponse) {}

    @Transactional
    public AuthResponse inscrire(InscriptionEtablissementRequest req) {
        ci.esatic.sigep.security.PolitiqueMotDePasse.exiger(req.getAdminPassword(), req.getAdminEmail());
        return creer(req, false).reponse();
    }

    /**
     * Inscription publique. Le formulaire ne doit pas dire si une adresse a déjà un compte :
     * dans ce cas rien n'est créé, le titulaire de l'adresse est prévenu par e-mail, et l'appelant
     * répond comme si l'inscription avait eu lieu. Le mot de passe est haché dans les deux cas,
     * pour que la durée de la réponse ne trahisse pas non plus l'adresse.
     *
     * @return l'inscription créée, ou vide si l'adresse avait déjà un compte
     */
    @Transactional
    public java.util.Optional<Inscription> demander(InscriptionEtablissementRequest req, boolean paiementAttendu) {
        // Vérifié avant de chercher l'adresse : la réponse est la même, compte existant ou non.
        ci.esatic.sigep.security.PolitiqueMotDePasse.exiger(req.getAdminPassword(), req.getAdminEmail());
        if (userRepository.existsByEmail(req.getAdminEmail())) {
            passwordEncoder.encode(req.getAdminPassword());
            mailService.notifierInscriptionAdresseConnue(req.getAdminEmail());
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(creer(req, paiementAttendu));
    }

    /**
     * Crée l'établissement et son administrateur.
     *
     * @param paiementAttendu l'inscription est suivie d'un paiement : l'e-mail « dossier en cours
     *                        d'examen » n'est pas envoyé, puisque c'est le paiement confirmé qui
     *                        activera l'espace, sans examen.
     */
    @Transactional
    public Inscription creer(InscriptionEtablissementRequest req, boolean paiementAttendu) {
        if (userRepository.existsByEmail(req.getAdminEmail())) {
            throw new IllegalArgumentException("Email déjà utilisé : " + req.getAdminEmail());
        }

        StatutEtablissement statutInitial = autoValidation
                ? StatutEtablissement.VALIDE : StatutEtablissement.EN_ATTENTE;

        Etablissement etablissement = etablissementRepository.save(Etablissement.builder()
                .nom(req.getNomEtablissement().trim())
                .slug(slugUnique(req.getNomEtablissement()))
                // SIGEP ne vend qu'en Côte d'Ivoire : c'est le seul pays où le paiement en ligne fonctionne.
                .paysCode(ci.esatic.sigep.annuaire.PaysUemoa.deCode(req.getPays())
                        .filter(ci.esatic.sigep.annuaire.PaysUemoa::isOuvert)
                        .orElse(ci.esatic.sigep.annuaire.PaysUemoa.CI).getCode())
                .plan(Plan.FREE)
                .maxEnseignants(freeMaxEnseignants)
                .statut(statutInitial)
                .actif(true)
                .kioskKey(genererKioskKey())      // C4 : clé kiosque QR propre au tenant, dès la création
                .build());
        // Rapprochement avec les listes officielles de l'État : reconnu, ou à vérifier par la plateforme.
        reconnaissanceService.rapprocher(etablissement);

        Role adminRole = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseThrow(() -> new RuntimeException("Rôle ADMIN non trouvé en base"));

        User admin = userRepository.save(User.builder()
                .email(req.getAdminEmail())
                .password(passwordEncoder.encode(req.getAdminPassword()))
                .roles(Set.of(adminRole))
                .etablissement(etablissement)   // rattachement au nouveau tenant
                .build());

        if (autoValidation) {
            mailService.notifierEtablissementValide(admin.getEmail(), etablissement.getNom());
        } else if (!paiementAttendu) {
            mailService.notifierInscriptionEtablissementRecue(
                    admin.getEmail(), req.getAdminPrenom(), etablissement.getNom());
        }

        // Pas de token : le compte ne devient utilisable qu'après validation du dossier.
        AuthResponse reponse = AuthResponse.builder()
                .id(admin.getId())
                .email(admin.getEmail())
                .roles(List.of(ERole.ROLE_ADMIN.name()))
                .nom(req.getAdminNom())
                .prenom(req.getAdminPrenom())
                .build();
        return new Inscription(etablissement, admin, reponse);
    }

    /**
     * Création DIRECTE par le SUPER ADMIN : le tenant naît immédiatement {@code VALIDE} et actif
     * (aucune étape de validation, puisque c'est le propriétaire de la plateforme qui le crée),
     * avec le plan choisi et son premier administrateur. Un e-mail « espace prêt » est envoyé.
     */
    @Transactional
    public Etablissement creerParSuperAdmin(String nom, String adminEmail, String adminPassword,
                                            String adminNom, String adminPrenom, Plan plan) {
        if (userRepository.existsByEmail(adminEmail)) {
            throw new IllegalArgumentException("Email déjà utilisé : " + adminEmail);
        }
        Plan p = plan != null ? plan : Plan.FREE;

        Etablissement etablissement = etablissementRepository.save(Etablissement.builder()
                .nom(nom.trim())
                .slug(slugUnique(nom))
                .plan(p)
                .maxEnseignants(p == Plan.FREE ? freeMaxEnseignants : 0)   // Pro/Entreprise = illimité
                .statut(StatutEtablissement.VALIDE)
                .actif(true)
                .kioskKey(genererKioskKey())
                .build());
        reconnaissanceService.rapprocher(etablissement);

        Role adminRole = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseThrow(() -> new RuntimeException("Rôle ADMIN non trouvé en base"));

        userRepository.save(User.builder()
                .email(adminEmail.trim())
                .password(passwordEncoder.encode(adminPassword))
                .roles(Set.of(adminRole))
                .etablissement(etablissement)
                .build());

        mailService.notifierEtablissementValide(adminEmail.trim(), etablissement.getNom());
        return etablissement;
    }

    /** Clé kiosque QR aléatoire (autorise l'affichage du QR de ce tenant — C4). */
    private String genererKioskKey() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
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
