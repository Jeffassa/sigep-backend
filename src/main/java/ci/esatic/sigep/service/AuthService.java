package ci.esatic.sigep.service;

import ci.esatic.sigep.dto.request.ChangerMotDePasseRequest;
import ci.esatic.sigep.dto.request.InscriptionRequest;
import ci.esatic.sigep.dto.request.LoginRequest;
import ci.esatic.sigep.dto.request.RegisterEnseignantRequest;
import ci.esatic.sigep.dto.response.AuthResponse;
import ci.esatic.sigep.entity.*;
import ci.esatic.sigep.exception.CompteNonValideException;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.security.JwtService;
import ci.esatic.sigep.tenant.TenantContext;
import ci.esatic.sigep.tenant.plan.PlanService;
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
    private final EtablissementRepository etablissementRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuthenticationManager authenticationManager;
    private final PlanService planService;
    private final AbonnementService abonnementService;
    private final CompteEnseignantService compteEnseignantService;
    private final MailService mailService;

    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = (User) authentication.getPrincipal();

        // Après la vérification du mot de passe, pour ne rien apprendre à qui n'en a pas.
        refuserCompteAdministration(user);

        // Un vacataire dont l'établissement habituel est fermé travaille dans un autre.
        List<CompteEnseignantService.Fiche> fiches = compteEnseignantService.fiches(user.getId());
        choisirEtablissementUtilisable(user, fiches);

        // SA-2 : l'établissement doit avoir été validé par le super admin (dossier d'inscription).
        verifierEtablissementValide(user);

        // Un enseignant ne peut se connecter que si l'administration a validé son compte.
        // (Les comptes administrateurs n'ont pas de profil enseignant : ils ne sont pas concernés.)
        CompteEnseignantService.Fiche fiche = ficheActive(user, fiches);
        verifierStatutFiche(fiche);

        return reponse(user, fiche, fiches, jwtService.generateToken(user), refreshTokenService.create(user));
    }

    /** Fait d'un autre établissement du compte l'établissement de travail. Effet immédiat. */
    @Transactional
    public AuthResponse changerEtablissement(String email, Long etablissementId) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new MetierException("UTILISATEUR_INTROUVABLE", "Compte introuvable"));
        List<CompteEnseignantService.Fiche> fiches = compteEnseignantService.fiches(user.getId());
        CompteEnseignantService.Fiche fiche = fiches.stream()
                .filter(f -> java.util.Objects.equals(f.etablissementId(), etablissementId))
                .findFirst()
                .orElseThrow(() -> new MetierException("ETABLISSEMENT_INCONNU",
                        "Vous n'enseignez pas dans cet établissement."));
        if (!utilisable(fiche)) {
            throw new CompteNonValideException("Cet établissement n'est pas accessible pour le moment.");
        }
        user.setEtablissement(etablissementRepository.findById(etablissementId).orElseThrow());
        userRepository.save(user);
        return reponse(user, fiche, fiches, null, null);
    }

    private void choisirEtablissementUtilisable(User user, List<CompteEnseignantService.Fiche> fiches) {
        if (fiches.size() < 2 || utilisable(ficheActive(user, fiches))) return;
        fiches.stream().filter(this::utilisable).findFirst().ifPresent(f -> {
            user.setEtablissement(etablissementRepository.findById(f.etablissementId()).orElseThrow());
            userRepository.save(user);
        });
    }

    private boolean utilisable(CompteEnseignantService.Fiche fiche) {
        if (fiche == null || fiche.statut() != StatutEnseignant.VALIDATED || fiche.etablissementId() == null) {
            return false;
        }
        Etablissement e = etablissementRepository.findById(fiche.etablissementId()).orElse(null);
        return e != null && !abonnementService.accesSuspendu(e)
                && (e.getStatut() == null || e.getStatut() == StatutEtablissement.VALIDE);
    }

    private CompteEnseignantService.Fiche ficheActive(User user, List<CompteEnseignantService.Fiche> fiches) {
        if (fiches.size() < 2) return fiches.stream().findFirst().orElse(null);
        Long actif = user.getEtablissement() == null ? null : user.getEtablissement().getId();
        return fiches.stream().filter(f -> java.util.Objects.equals(f.etablissementId(), actif)).findFirst().orElse(null);
    }

    private void verifierStatutFiche(CompteEnseignantService.Fiche fiche) {
        if (fiche == null) return;
        if (fiche.statut() == StatutEnseignant.PENDING) {
            throw new CompteNonValideException(
                    "Votre compte est en attente de validation par l'administration.");
        }
        if (fiche.statut() == StatutEnseignant.ARCHIVE) {
            throw new CompteNonValideException(
                    "Votre compte a été archivé. Veuillez contacter l'administration.");
        }
        if (fiche.statut() == StatutEnseignant.REJECTED) {
            throw new CompteNonValideException(
                    "Votre compte a été refusé. Veuillez contacter l'administration.");
        }
    }

    private AuthResponse reponse(User user, CompteEnseignantService.Fiche fiche,
                                 List<CompteEnseignantService.Fiche> fiches, String token, String refreshToken) {
        return AuthResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .type("Bearer")
                .id(user.getId())
                .email(user.getEmail())
                .roles(user.getRoles().stream().map(role -> role.getName().name()).collect(Collectors.toList()))
                .nom(fiche == null ? "" : fiche.nom())
                .prenom(fiche == null ? "" : fiche.prenom())
                .etablissementId(user.getEtablissement() != null ? user.getEtablissement().getId() : null)
                .etablissementNom(user.getEtablissement() != null ? user.getEtablissement().getNomEffectif() : null)
                .etablissements(fiches.stream().filter(this::utilisable)
                        .map(f -> new AuthResponse.EtablissementAccessible(f.etablissementId(),
                                etablissementRepository.findById(f.etablissementId()).orElseThrow().getNomEffectif()))
                        .toList())
                .mustChangePassword(user.isMustChangePassword())
                .build();
    }

    /**
     * Échange un refresh token valide contre un nouvel access token.
     * SECURITE : rotation systématique — l'ancien refresh token est invalidé et un nouveau est émis.
     */
    @Transactional
    public AuthResponse refresh(String refreshToken) {
        var rt = refreshTokenService.verifier(refreshToken);
        User user = rt.getUser();

        // SECURITE : le compte doit TOUJOURS être autorisé. Un enseignant rejeté/suspendu
        // ou un utilisateur désactivé après connexion ne doit pas pouvoir prolonger sa
        // session via le refresh (le gating de validation ne doit pas être contournable).
        List<CompteEnseignantService.Fiche> fiches = compteEnseignantService.fiches(user.getId());
        choisirEtablissementUtilisable(user, fiches);
        CompteEnseignantService.Fiche fiche = ficheActive(user, fiches);
        verifierCompteToujoursAutorise(user, fiche);

        String nouveauRefresh = refreshTokenService.rotation(rt);
        return reponse(user, fiche, fiches, jwtService.generateToken(user), nouveauRefresh);
    }

    /** Déconnexion : révoque le refresh token fourni (l'access token expire seul, à court terme). */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoquer(refreshToken);
    }

    /**
     * Changement de mot de passe par l'utilisateur lui-même (espace profil de l'app mobile).
     *
     * <p>Trois garde-fous :
     * <ul>
     *   <li>l'ancien mot de passe est EXIGÉ — un access token volé ne suffit donc pas à
     *       verrouiller le compte de sa victime ;</li>
     *   <li>le nouveau doit différer de l'ancien ;</li>
     *   <li>tous les refresh tokens sont révoqués : si un tiers avait ouvert une session, elle
     *       devient inutilisable. C'est l'intérêt principal d'un changement de mot de passe
     *       après un soupçon de compromission.</li>
     * </ul>
     */
    @Transactional
    public void changerMotDePasse(String email, ChangerMotDePasseRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new MetierException("UTILISATEUR_INTROUVABLE", "Compte introuvable"));

        if (!passwordEncoder.matches(request.getAncienMotDePasse(), user.getPassword())) {
            throw new MetierException("MOT_DE_PASSE_INCORRECT", "Le mot de passe actuel est incorrect");
        }
        if (passwordEncoder.matches(request.getNouveauMotDePasse(), user.getPassword())) {
            throw new MetierException("MOT_DE_PASSE_IDENTIQUE",
                    "Le nouveau mot de passe doit être différent de l'actuel");
        }

        user.setPassword(passwordEncoder.encode(request.getNouveauMotDePasse()));
        user.setMustChangePassword(false);
        userRepository.save(user);
        refreshTokenService.revoquerTout(user);
    }

    /**
     * Vérifie que le compte est toujours autorisé au moment du refresh.
     * On se contente de bloquer (throw) : chaque tentative de refresh étant revérifiée,
     * un compte rejeté/désactivé ne peut plus obtenir d'access token. La révocation
     * effective des refresh tokens est faite au moment de la décision admin
     * ({@code EnseignantService.updateStatut}).
     */
    private void verifierCompteToujoursAutorise(User user, CompteEnseignantService.Fiche fiche) {
        if (!user.isEnabled()) {
            throw new CompteNonValideException("Compte désactivé. Veuillez contacter l'administration.");
        }
        refuserCompteAdministration(user);
        verifierEtablissementValide(user);
        verifierStatutFiche(fiche);
    }

    /**
     * Les comptes d'administration ne reçoivent pas de jeton d'API.
     *
     * <p>Leur connexion passe par le second facteur de l'espace web. Un jeton délivré ici sur le
     * seul mot de passe ouvrirait les mêmes données sans ce second facteur, et le client de cette
     * route, l'application mobile, ne sert que les enseignants. Le refresh applique la même règle
     * pour fermer aussi les sessions déjà ouvertes.
     */
    private void refuserCompteAdministration(User user) {
        boolean administration = user.getAuthorities().stream().anyMatch(a ->
                !ERole.ROLE_ENSEIGNANT.name().equals(a.getAuthority()));
        if (administration) {
            throw new CompteNonValideException(
                    "Les comptes d'administration se connectent depuis l'espace web.");
        }
    }

    /**
     * SA-2 : le dossier d'inscription de l'établissement doit avoir été validé par le
     * SUPER ADMIN. Tant qu'il est EN_ATTENTE (ou s'il a été REFUSÉ), aucune connexion
     * ni aucun refresh n'aboutit pour les comptes de cet établissement. Il en va de même
     * quand l'accès est suspendu : abonnement expiré ou désactivation par la plateforme.
     */
    private void verifierEtablissementValide(User user) {
        Etablissement e = user.getEtablissement();
        if (e == null) return;
        if (abonnementService.accesSuspendu(e)) {
            throw new CompteNonValideException(
                    "L'accès de votre établissement est suspendu. Contactez votre administration.");
        }
        if (e.getStatut() == null) return;
        if (e.getStatut() == StatutEtablissement.EN_ATTENTE) {
            throw new CompteNonValideException(
                    "Votre dossier d'inscription est en cours d'analyse. "
                    + "Vous recevrez un e-mail dès la validation de votre espace — merci de patienter.");
        }
        if (e.getStatut() == StatutEtablissement.REFUSE) {
            throw new CompteNonValideException(
                    "Votre dossier d'inscription n'a pas été retenu. Contactez-nous pour en savoir plus.");
        }
    }

    /**
     * Auto-inscription d'un enseignant via son matricule. Le matricule doit déjà exister
     * dans l'annuaire (importé par l'admin) et ne pas avoir de compte. Le compte est créé
     * en attente (PENDING) : aucun token n'est délivré tant que l'admin n'a pas validé.
     */
    @Transactional
    public AuthResponse inscription(InscriptionRequest request) {
        // Depuis V16, le matricule est unique PAR établissement (pas globalement) : findByMatricule
        // lèverait NonUniqueResult (HTTP 500) si deux tenants partagent un matricule. On liste et on
        // tranche proprement.
        java.util.List<Enseignant> candidats = enseignantRepository.findAllByMatricule(request.getMatricule());
        if (candidats.size() > 1) {
            throw new IllegalArgumentException(
                    "Plusieurs établissements correspondent à ce matricule. Contactez votre administration "
                  + "pour finaliser votre inscription.");
        }
        Enseignant enseignant = candidats.stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Matricule introuvable. Vérifiez-le ou contactez l'administration."));
        if (enseignant.getUser() != null) {
            throw new IllegalArgumentException("Ce matricule a déjà un compte. Connectez-vous.");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email déjà utilisé : " + request.getEmail());
        }

        Role role = roleRepository.findByName(ERole.ROLE_ENSEIGNANT)
                .orElseThrow(() -> new RuntimeException("Rôle ENSEIGNANT non trouvé en base"));
        // Multi-tenant : le compte hérite de l'établissement de l'enseignant (annuaire importé).
        Etablissement tenant = enseignant.getEtablissementId() == null ? null
                : etablissementRepository.findById(enseignant.getEtablissementId()).orElse(null);
        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .roles(Set.of(role))
                .etablissement(tenant)
                .build();
        user = userRepository.save(user);

        enseignant.setUser(user);
        enseignant.setStatut(StatutEnseignant.PENDING);
        enseignantRepository.save(enseignant);

        return AuthResponse.builder()
                .token(null)
                .type("Bearer")
                .id(user.getId())
                .email(user.getEmail())
                .roles(List.of(ERole.ROLE_ENSEIGNANT.name()))
                .nom(enseignant.getNom())
                .prenom(enseignant.getPrenom())
                .mustChangePassword(user.isMustChangePassword())
                .build();
    }

    @Transactional
    public AuthResponse registerEnseignant(RegisterEnseignantRequest request) {
        if (enseignantRepository.existsByMatricule(request.getMatricule())) {
            throw new IllegalArgumentException("Matricule déjà utilisé : " + request.getMatricule());
        }

        // Multi-tenant : on rattache le nouvel enseignant à l'établissement de l'admin
        // courant et on applique le quota du plan. (En l'absence de tenant — contexte hors
        // requête / tests — on ne bloque pas et on laisse l'estampillage automatique opérer.)
        Etablissement tenant = etablissementCourant();
        if (tenant != null) {
            planService.verifierQuotaEnseignant(tenant,
                    enseignantRepository.countByEtablissementId(tenant.getId()));
        }

        Role role = roleRepository.findByName(ERole.ROLE_ENSEIGNANT)
                .orElseThrow(() -> new RuntimeException("Rôle ENSEIGNANT non trouvé en base"));

        // Un vacataire déjà inscrit ailleurs garde son compte : le mot de passe reçu est ignoré.
        User existant = compteEnseignantService
                .compteARattacher(request.getEmail(), tenant != null ? tenant.getId() : null).orElse(null);
        User user = existant != null ? existant : userRepository.save(User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .mustChangePassword(false)
                .roles(Set.of(role))
                .etablissement(tenant)   // SECURITE : le nouvel utilisateur appartient au tenant courant
                .build());

        Enseignant enseignant = Enseignant.builder()
                .matricule(request.getMatricule())
                .nom(request.getNom())
                .prenom(request.getPrenom())
                .departement(request.getDepartement())
                .grade(request.getGrade())
                // E2 : compte créé PAR l'admin (avec identifiants) → PRÉ-VALIDÉ, utilisable
                // immédiatement (pas de double validation). L'auto-inscription reste PENDING.
                .statut(StatutEnseignant.VALIDATED)
                .user(user)
                .etablissementId(tenant != null ? tenant.getId() : null)
                .build();
        enseignantRepository.save(enseignant);
        if (existant != null && tenant != null) {
            mailService.notifierRattachementEtablissement(tenant.getEmailFrom(), existant.getEmail(),
                    request.getPrenom(), tenant.getNomEffectif());
        }

        // Aucun token délivré ici : l'enseignant se connecte lui-même avec ses identifiants
        // (compte déjà validé, aucune étape d'attente).
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

    /** Établissement (tenant) de la requête courante, ou null hors contexte tenant. */
    private Etablissement etablissementCourant() {
        Long tenantId = TenantContext.get();
        return tenantId == null ? null : etablissementRepository.findById(tenantId).orElse(null);
    }
}
