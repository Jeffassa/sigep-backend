package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.entity.Role;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.exception.ResourceNotFoundException;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.RoleRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.security.SecurityUtils;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gestion des enseignants, commune au formulaire de la console et à l'agent.
 *
 * <p>Les règles vivaient dans le contrôleur du formulaire. Un second chemin d'écriture — l'agent —
 * aurait dû les recopier, et la première divergence aurait ouvert une porte : un quota contourné,
 * un compte créé sans établissement. Elles sont donc ici, une seule fois, et les deux chemins
 * passent par elles.
 *
 * <p>Chaque écriture a sa vérification sans effet ({@code verifier…}) : c'est elle que l'agent
 * appelle en simulation, et c'est elle aussi que l'écriture appelle d'abord. Une simulation ne
 * peut donc pas annoncer un succès que l'écriture refuserait ensuite.
 */
@Service
@RequiredArgsConstructor
public class GestionEnseignantsService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final EnseignantRepository enseignantRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlanService planService;
    private final EtablissementCourantService etablissementCourantService;
    private final CompteEnseignantService compteEnseignantService;
    private final MailService mailService;

    /** Ce que l'administration saisit pour créer un enseignant. */
    public record NouvelEnseignant(String matricule, String nom, String prenom,
                                   String departement, String grade, String email) {

        /** Espaces de bord retirés, champs facultatifs vides ramenés à null. */
        public NouvelEnseignant normalise() {
            return new NouvelEnseignant(net(matricule), net(nom), net(prenom),
                    vide(departement), vide(grade), net(email));
        }

        private static String net(String v) {
            return v == null ? null : v.trim();
        }

        private static String vide(String v) {
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    /** Une fiche neuve avec un compte neuf, ou une fiche rattachée à un compte qui existe déjà. */
    public enum Issue { CREE, RATTACHE }

    /** Résultat d'une création ; l'enseignant est nul en simulation. */
    public record Creation(Issue issue, Enseignant enseignant) { }

    /**
     * Applique toutes les règles de création sans rien écrire.
     *
     * @return ce que la création ferait
     * @throws MetierException si la création serait refusée, avec un code stable
     */
    @Transactional(readOnly = true)
    public Issue verifierCreation(NouvelEnseignant saisie) {
        NouvelEnseignant d = saisie.normalise();
        exigerRempli(d.matricule(), "le matricule");
        exigerRempli(d.nom(), "le nom");
        exigerRempli(d.prenom(), "le prénom");
        exigerRempli(d.email(), "l'e-mail");
        if (!EMAIL.matcher(d.email()).matches()) {
            throw new MetierException("EMAIL_INVALIDE", "L'adresse " + d.email() + " n'est pas une adresse e-mail.");
        }

        // Quota du plan (Free ≤ 10 enseignants) : appliqué sur TOUS les chemins de création.
        Etablissement etab = etablissementCourantService.courant();
        if (etab != null && planService.quotaEnseignantsAtteint(etab,
                enseignantRepository.countByEtablissementId(etab.getId()))) {
            throw new MetierException("QUOTA_ATTEINT", "Votre plan permet " + etab.getMaxEnseignants()
                    + " enseignants, et ce nombre est atteint. Passez au plan Pro pour en ajouter d'autres.");
        }
        if (enseignantRepository.existsByMatricule(d.matricule())) {
            throw new MetierException("MATRICULE_PRIS", "Le matricule " + d.matricule() + " est déjà utilisé.");
        }
        try {
            return compteEnseignantService.compteARattacher(d.email(), etab != null ? etab.getId() : null)
                    .isPresent() ? Issue.RATTACHE : Issue.CREE;
        } catch (IllegalArgumentException e) {
            throw new MetierException("EMAIL_INUTILISABLE",
                    "Impossible d'utiliser " + d.email() + " : " + e.getMessage() + ".");
        }
    }

    /**
     * Crée l'enseignant et lui écrit : ses accès provisoires, ou, s'il a déjà un compte SIGEP
     * ailleurs, l'annonce de son rattachement — son mot de passe ne change pas.
     */
    @Transactional
    public Creation creer(NouvelEnseignant saisie) {
        verifierCreation(saisie);
        NouvelEnseignant d = saisie.normalise();
        Etablissement etab = etablissementCourantService.courant();
        String expediteur = etab != null ? etab.getEmailFrom() : null;

        User compteExistant = compteEnseignantService
                .compteARattacher(d.email(), etab != null ? etab.getId() : null).orElse(null);

        // Vacataire déjà inscrit ailleurs : sa fiche rejoint ce compte, dont le mot de passe ne change pas.
        if (compteExistant != null) {
            Enseignant fiche = enseignantRepository.save(fiche(d, compteExistant));
            mailService.notifierRattachementEtablissement(expediteur, d.email(), d.prenom(),
                    etab != null ? etab.getNomEffectif() : "Un établissement");
            return new Creation(Issue.RATTACHE, fiche);
        }

        Role role = roleRepository.findByName(ERole.ROLE_ENSEIGNANT).orElseThrow();
        String motDePasse = SecurityUtils.genererMotDePasseProvisoire();

        // E1 (isolation) : rattacher le compte à l'établissement courant, comme les autres
        // chemins de création (AuthService.registerEnseignant/inscription, OnboardingService).
        // Sans cela, user.etablissement=null → à la connexion mobile, aucun tenant posé → filtre
        // Hibernate + garde @PostLoad désactivés → lecture inter-tenant possible.
        User compte = userRepository.save(User.builder()
                .email(d.email())
                .password(passwordEncoder.encode(motDePasse))
                .mustChangePassword(true)
                .roles(Set.of(role))
                .etablissement(etab)
                .build());

        Enseignant fiche = enseignantRepository.save(fiche(d, compte));
        mailService.notifierIdentifiantsProvisoires(expediteur, d.email(), d.prenom(), motDePasse);
        return new Creation(Issue.CREE, fiche);
    }

    /**
     * Vérifie qu'on peut renvoyer des accès à cet enseignant, sans rien changer.
     *
     * @return l'adresse à laquelle ils partiraient
     */
    @Transactional(readOnly = true)
    public String verifierRenvoiAcces(Long id) {
        Enseignant enseignant = enseignantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Enseignant", "id", id));
        User compte = enseignant.getUser();
        if (compte == null) {
            throw new MetierException("SANS_COMPTE",
                    "Cet enseignant n'a pas de compte : renseignez son e-mail en le recreant.");
        }
        // Compte partagé : seul l'établissement où l'enseignant travaille peut lui renvoyer des
        // accès, pour qu'un autre ne puisse pas fermer ses sessions à répétition.
        Long ici = enseignant.getEtablissementId();
        boolean partage = compteEnseignantService.fiches(compte.getId()).size() > 1;
        if (partage && (compte.getEtablissement() == null || !compte.getEtablissement().getId().equals(ici))) {
            throw new MetierException("COMPTE_PARTAGE", "Ce compte est partagé avec un autre établissement : "
                    + "l'enseignant doit choisir le vôtre dans l'application, ou demander ses accès là où il travaille.");
        }
        return compte.getEmail();
    }

    /**
     * Régénère un mot de passe provisoire et le renvoie par courriel.
     *
     * <p>Sans cette action, un envoi perdu — filtré, mal saisi, ou jamais parti — laissait
     * l'enseignant sans aucun accès et sans recours : le secret n'est stocké nulle part en clair,
     * et l'administration ne peut donc pas le lui redonner.
     *
     * @return l'adresse à laquelle les accès sont partis
     */
    @Transactional
    public String renvoyerAcces(Long id) {
        verifierRenvoiAcces(id);
        Enseignant enseignant = enseignantRepository.findById(id).orElseThrow();
        User compte = enseignant.getUser();
        String secret = SecurityUtils.genererMotDePasseProvisoire();
        compte.setPassword(passwordEncoder.encode(secret));
        // L'ancien secret devient caduc et le nouveau devra être remplacé à la connexion.
        compte.setMustChangePassword(true);
        userRepository.save(compte);

        Etablissement etab = etablissementCourantService.courant();
        mailService.notifierIdentifiantsProvisoires(etab != null ? etab.getEmailFrom() : null,
                compte.getEmail(), enseignant.getPrenom(), secret);
        return compte.getEmail();
    }

    /**
     * Adresses qui recevraient un message : celles d'un enseignant, ou de tous ceux qui ont un compte.
     *
     * @param enseignantId un enseignant précis, ou {@code null} pour tous
     */
    @Transactional(readOnly = true)
    public List<String> destinataires(Long enseignantId) {
        if (enseignantId == null) {
            return enseignantRepository.findAll().stream()
                    .filter(e -> e.getUser() != null && e.getUser().getEmail() != null)
                    .map(e -> e.getUser().getEmail())
                    .toList();
        }
        return enseignantRepository.findById(enseignantId)
                .filter(e -> e.getUser() != null && e.getUser().getEmail() != null)
                .map(e -> List.of(e.getUser().getEmail()))
                .orElse(List.of());
    }

    /** Envoie le message et rend le nombre de courriels partis. */
    public int envoyerMessage(Long enseignantId, String sujet, String corps) {
        List<String> adresses = destinataires(enseignantId);
        for (String adresse : adresses) {
            mailService.envoyerMessage(adresse, sujet, corps);
        }
        return adresses.size();
    }

    private static Enseignant fiche(NouvelEnseignant d, User compte) {
        return Enseignant.builder()
                .matricule(d.matricule())
                .nom(d.nom())
                .prenom(d.prenom())
                .departement(d.departement())
                .grade(d.grade())
                // VALIDATED, pas PENDING : c'est l'administration elle-même qui vient de créer ce
                // compte et d'en envoyer le mot de passe. Le laisser en attente lui ferait recevoir
                // des identifiants refusés à la connexion, puisque PENDING bloque le login.
                .statut(StatutEnseignant.VALIDATED)
                .user(compte)
                .build();
    }

    private static void exigerRempli(String valeur, String libelle) {
        if (valeur == null || valeur.isBlank()) {
            throw new MetierException("CHAMP_MANQUANT", "Il manque " + libelle + ".");
        }
    }
}
