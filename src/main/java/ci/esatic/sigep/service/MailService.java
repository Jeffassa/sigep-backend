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
        envoyer(email, "SIGEP — Votre dossier d'inscription est bien reçu",
                bonjour + "\n\n"
                + "Merci d'avoir inscrit « " + nomEtablissement + " » sur SIGEP !\n\n"
                + "Votre dossier est en cours d'analyse par notre équipe. Cette vérification est "
                + "généralement rapide : vous recevrez un e-mail dès que votre espace sera activé.\n\n"
                + "Merci de patienter — nous revenons vers vous très vite.\n\n"
                + "— L'équipe SIGEP · Solutions de Gestion");
    }

    /** Le super admin a validé le dossier : l'espace est actif. */
    @Async
    public void notifierEtablissementValide(String email, String nomEtablissement) {
        envoyer(email, "SIGEP — Votre espace est activé !",
                "Bonjour,\n\n"
                + "Bonne nouvelle : le dossier de « " + nomEtablissement + " » a été validé.\n\n"
                + "Votre espace SIGEP est maintenant actif. Connectez-vous avec votre e-mail et "
                + "votre mot de passe :\n"
                + baseUrl + "/admin-login\n\n"
                + "Bienvenue, et bonne gestion !\n\n"
                + "— L'équipe SIGEP · Solutions de Gestion");
    }

    /** Reçu après un paiement en ligne (Stripe) réussi. */
    @Async
    public void notifierPaiementEnLigne(String email, String nomEtablissement, long montant,
                                        java.time.LocalDate dateExpiration) {
        String jusqua = dateExpiration != null
                ? dateExpiration.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                : "—";
        envoyer(email, "SIGEP — Paiement reçu, merci !",
                "Bonjour,\n\n"
                + "Nous confirmons la réception de votre paiement de " + montant + " FCFA pour « "
                + nomEtablissement + " ».\n\n"
                + "Votre abonnement Pro est actif jusqu'au " + jusqua + ".\n\n"
                + "Merci de votre confiance !\n\n"
                + "— L'équipe SIGEP · Solutions de Gestion");
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
            sujet = "SIGEP — Votre abonnement a expiré";
            intro = "L'abonnement de « " + nomEtablissement + " » a expiré (" + quand + ").";
        } else {
            sujet = "SIGEP — Votre abonnement expire bientôt (J-" + jours + ")";
            intro = "L'abonnement de « " + nomEtablissement + " » expire le " + quand
                    + " (dans " + jours + " jour(s)).";
        }
        envoyer(email, sujet,
                "Bonjour,\n\n" + intro + "\n\n"
                + "Pour éviter toute interruption d'accès, renouvelez depuis votre espace :\n"
                + baseUrl + "/admin/abonnement\n\n"
                + "— L'équipe SIGEP · Solutions de Gestion");
    }

    /** Le super admin a refusé le dossier. */
    @Async
    public void notifierEtablissementRefuse(String email, String nomEtablissement) {
        envoyer(email, "SIGEP — Suite de votre dossier d'inscription",
                "Bonjour,\n\n"
                + "Après examen, nous ne pouvons pas activer l'espace « " + nomEtablissement
                + " » pour le moment.\n\n"
                + "Pour en discuter ou compléter votre dossier, écrivez-nous : " + contactPlateforme + "\n\n"
                + "— L'équipe SIGEP · Solutions de Gestion");
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
            envoyer(expediteur, email, "SIGEP — Votre compte est validé",
                    "Bonjour " + prenom + ",\n\nVotre compte enseignant SIGEP a été validé par l'administration. "
                    + "Vous pouvez désormais vous connecter à l'application.\n\n— SIGEP");
        } else {
            envoyer(expediteur, email, "SIGEP — Votre compte a été refusé",
                    "Bonjour " + prenom + ",\n\nVotre demande de compte enseignant SIGEP a été refusée. "
                    + "Veuillez contacter l'administration.\n\n— SIGEP");
        }
    }

    @Async
    public void notifierDecisionRattrapage(String expediteur, String email, String prenom, String matiere,
                                           String quand, boolean accepte) {
        if (accepte) {
            envoyer(expediteur, email, "SIGEP — Rattrapage accepté",
                    "Bonjour " + prenom + ",\n\nVotre demande de rattrapage (" + matiere + ") a été ACCEPTÉE "
                    + "pour le " + quand + ".\n\n— SIGEP");
        } else {
            envoyer(expediteur, email, "SIGEP — Rattrapage refusé",
                    "Bonjour " + prenom + ",\n\nVotre demande de rattrapage (" + matiere + ") a été refusée.\n\n— SIGEP");
        }
    }

    @Async
    public void notifierSeancesNonEmargees(String expediteur, String email, String prenom, List<String> lignes) {
        if (email == null || lignes == null || lignes.isEmpty()) return;
        String corps = "Bonjour " + prenom + ",\n\nVous avez " + lignes.size()
                + " séance(s) non émargée(s) aujourd'hui :\n"
                + String.join("\n", lignes)
                + "\n\nPensez à régulariser votre émargement.\n\n— SIGEP";
        envoyer(expediteur, email, "SIGEP — Séances non émargées", corps);
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
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(realFrom);
            msg.setTo(destinataire);
            msg.setSubject(sujet);
            msg.setText(corps);
            sender.send(msg);
            log.info("E-mail envoyé à {} : {}", destinataire, sujet);
        } catch (Exception e) {
            log.error("Échec envoi e-mail à {} : {}", destinataire, e.getMessage());
        }
    }
}
