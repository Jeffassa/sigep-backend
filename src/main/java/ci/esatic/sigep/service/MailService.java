package ci.esatic.sigep.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Envoi d'e-mails de notification (asynchrone).
 * Désactivé par défaut ({@code app.mail.enabled=false}) : en l'absence de SMTP
 * configuré, les envois sont seulement journalisés (le démarrage ne casse pas).
 * En production : définir spring.mail.* + app.mail.enabled=true.
 */
@Service
@Slf4j
public class MailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${app.mail.enabled:false}")
    private boolean enabled;

    @Value("${app.mail.from:no-reply@esatic.ci}")
    private String from;

    @Value("${app.platform.contact-email:contact@sigep.store}")
    private String contactPlateforme;

    @Value("${app.base-url:https://sigep.store}")
    private String baseUrl;

    public MailService(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    // ─── SA-2 : cycle de validation d'un établissement ────────────────────────

    /** Après l'inscription self-service : le dossier est reçu et en cours d'analyse. */
    @Async
    public void notifierInscriptionEtablissementRecue(String email, String prenom, String nomEtablissement) {
        String bonjour = (prenom == null || prenom.isBlank()) ? "Bonjour," : "Bonjour " + prenom + ",";
        envoyer(email, "SIGEP - Votre dossier d'inscription est bien reçu",
                bonjour + "\n\n"
                + "Nous avons bien reçu l'inscription de « " + nomEtablissement + " ».\n\n"
                + "Votre dossier est en cours d'examen. Vous recevrez un e-mail dès que votre "
                + "espace sera activé.\n\n"
                + "L'équipe SIGEP");
    }

    /** Le super admin a validé le dossier : l'espace est actif. */
    @Async
    public void notifierEtablissementValide(String email, String nomEtablissement) {
        envoyer(email, "SIGEP - Votre espace est activé",
                "Bonjour,\n\n"
                + "Le dossier de « " + nomEtablissement + " » a été validé : votre espace SIGEP "
                + "est actif.\n\n"
                + "Connectez-vous avec votre e-mail et votre mot de passe :\n"
                + baseUrl + "/admin-login\n\n"
                + "L'équipe SIGEP");
    }

    /** Reçu après un paiement en ligne (Stripe) réussi. */
    @Async
    public void notifierPaiementEnLigne(String email, String nomEtablissement, long montant,
                                        java.time.LocalDate dateExpiration) {
        String jusqua = dateExpiration != null
                ? dateExpiration.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                : "—";
        envoyer(email, "SIGEP - Paiement reçu",
                "Bonjour,\n\n"
                + "Nous avons reçu votre paiement de " + montant + " FCFA pour « "
                + nomEtablissement + " ».\n\n"
                + "Votre abonnement Pro est actif jusqu'au " + jusqua + ".\n\n"
                + "Merci de votre confiance.\n\n"
                + "L'équipe SIGEP");
    }

    /** Relance d'expiration d'abonnement (dunning E15). jours > 0 : à venir ; jours <= 0 : expiré. */
    @Async
    public void notifierExpirationAbonnement(String email, String nomEtablissement, long jours,
                                             java.time.LocalDate dateExpiration) {
        String quand = dateExpiration != null
                ? dateExpiration.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "—";
        String sujet;
        String intro;
        if (jours <= 0) {
            sujet = "SIGEP - Votre abonnement a expiré";
            intro = "L'abonnement de « " + nomEtablissement + " » a expiré (" + quand + ").";
        } else {
            sujet = "SIGEP - Votre abonnement expire bientôt (J-" + jours + ")";
            intro = "L'abonnement de « " + nomEtablissement + " » expire le " + quand
                    + " (dans " + jours + " jour(s)).";
        }
        envoyer(email, sujet,
                "Bonjour,\n\n" + intro + "\n\n"
                + "Pour éviter toute coupure d'accès, renouvelez-le depuis votre espace :\n"
                + baseUrl + "/admin/abonnement\n\n"
                + "L'équipe SIGEP");
    }

    /** Le super admin a refusé le dossier. */
    @Async
    public void notifierEtablissementRefuse(String email, String nomEtablissement) {
        envoyer(email, "SIGEP - Suite de votre dossier d'inscription",
                "Bonjour,\n\n"
                + "Après examen, nous ne pouvons pas activer l'espace « " + nomEtablissement
                + " » pour le moment.\n\n"
                + "Pour en discuter ou compléter votre dossier, écrivez-nous : " + contactPlateforme + "\n\n"
                + "L'équipe SIGEP");
    }

    /** Message libre envoyé par l'administration à un enseignant. */
    @Async
    public void envoyerMessage(String email, String sujet, String corps) {
        envoyer(email, sujet, corps);
    }

    // E9 : les e-mails MÉTIER (destinés aux enseignants d'un tenant) peuvent partir de
    // l'expéditeur PROPRE à l'établissement. `expediteur` null → expéditeur plateforme.
    @Async
    public void notifierStatutCompte(String expediteur, String email, String prenom, boolean valide) {
        if (valide) {
            envoyer(expediteur, email, "SIGEP - Votre compte est validé",
                    "Bonjour " + prenom + ",\n\nVotre compte enseignant a été validé par l'administration. "
                    + "Vous pouvez maintenant vous connecter à l'application.\n\nSIGEP");
        } else {
            envoyer(expediteur, email, "SIGEP - Votre compte a été refusé",
                    "Bonjour " + prenom + ",\n\nVotre demande de compte enseignant a été refusée. "
                    + "Rapprochez-vous de l'administration.\n\nSIGEP");
        }
    }

    /** Identifiants créés par l'administration : le secret provisoire n'est envoyé qu'ici. */
    @Async
    public void notifierIdentifiantsProvisoires(String expediteur, String email, String prenom,
                                                String motDePasseProvisoire) {
        envoyer(expediteur, email, "SIGEP - Vos identifiants enseignant",
                "Bonjour " + (prenom == null ? "" : prenom) + ",\n\n"
                + "Votre compte enseignant a été créé par l'administration de votre établissement.\n\n"
                + "Email : " + email + "\n"
                + "Mot de passe provisoire : " + motDePasseProvisoire + "\n\n"
                + "Connectez-vous, puis changez ce mot de passe depuis votre profil.\n\n"
                + "SIGEP");
    }

    @Async
    public void envoyerCodeOtpAdmin(String email, String code) {
        envoyer(email, "SIGEP - Code de vérification",
                "Bonjour,\n\nVotre code de vérification est : " + code + "\n\n"
                + "Ce code expire dans 5 minutes et ne peut être utilisé qu'une seule fois.\n\n"
                + "Si vous n'êtes pas à l'origine de cette connexion, prévenez le support SIGEP.\n\n"
                + "SIGEP");
    }

    @Async
    public void notifierDecisionRattrapage(String expediteur, String email, String prenom, String matiere,
                                           String quand, boolean accepte) {
        if (accepte) {
            envoyer(expediteur, email, "SIGEP - Rattrapage accepté",
                    "Bonjour " + prenom + ",\n\nVotre demande de rattrapage (" + matiere + ") a été acceptée "
                    + "pour le " + quand + ".\n\nSIGEP");
        } else {
            envoyer(expediteur, email, "SIGEP - Rattrapage refusé",
                    "Bonjour " + prenom + ",\n\nVotre demande de rattrapage (" + matiere + ") a été refusée.\n\nSIGEP");
        }
    }

    @Async
    public void notifierSeancesNonEmargees(String expediteur, String email, String prenom, List<String> lignes) {
        if (email == null || lignes == null || lignes.isEmpty()) return;
        String corps = "Bonjour " + prenom + ",\n\nVous avez " + lignes.size()
                + " séance(s) non émargée(s) aujourd'hui :\n"
                + String.join("\n", lignes)
                + "\n\nPensez à régulariser votre émargement.\n\nSIGEP";
        envoyer(expediteur, email, "SIGEP - Séances non émargées", corps);
    }

    /** Envoi depuis l'expéditeur plateforme (e-mails de niveau plateforme). */
    private void envoyer(String destinataire, String sujet, String corps) {
        envoyer(null, destinataire, sujet, corps);
    }

    /** Envoi avec expéditeur éventuellement propre au tenant (E9) ; null = expéditeur plateforme. */
    private void envoyer(String expediteur, String destinataire, String sujet, String corps) {
        if (destinataire == null || destinataire.isBlank()) return;
        String realFrom = (expediteur != null && !expediteur.isBlank()) ? expediteur : from;
        if (!enabled) {
            log.info("[MAIL désactivé] de={} à={} | sujet={}", realFrom, destinataire, sujet);
            return;
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("Envoi e-mail impossible : aucun JavaMailSender configuré (spring.mail.host manquant)");
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(realFrom);
        msg.setTo(destinataire);
        msg.setSubject(sujet);
        msg.setText(corps);

        // Fiabilité : jusqu'à 3 tentatives avec backoff (absorbe les défaillances SMTP transitoires).
        for (int tentative = 1; tentative <= MAX_TENTATIVES_MAIL; tentative++) {
            try {
                sender.send(msg);
                log.info("E-mail envoyé à {} : {}", destinataire, sujet);
                return;
            } catch (Exception e) {
                if (tentative >= MAX_TENTATIVES_MAIL) {
                    log.error("Échec envoi e-mail à {} après {} tentatives : {}",
                            destinataire, tentative, e.getMessage());
                    return;
                }
                log.warn("Échec envoi e-mail à {} (tentative {}/{}) : {} — nouvelle tentative",
                        destinataire, tentative, MAX_TENTATIVES_MAIL, e.getMessage());
                try {
                    Thread.sleep(2000L * tentative);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static final int MAX_TENTATIVES_MAIL = 3;
}
