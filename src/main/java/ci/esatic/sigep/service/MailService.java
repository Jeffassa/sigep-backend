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

    public MailService(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    @Async
    public void notifierStatutCompte(String email, String prenom, boolean valide) {
        if (valide) {
            envoyer(email, "SIGEP — Votre compte est validé",
                    "Bonjour " + prenom + ",\n\nVotre compte enseignant SIGEP a été validé par l'administration. "
                    + "Vous pouvez désormais vous connecter à l'application.\n\n— SIGEP / ESATIC");
        } else {
            envoyer(email, "SIGEP — Votre compte a été refusé",
                    "Bonjour " + prenom + ",\n\nVotre demande de compte enseignant SIGEP a été refusée. "
                    + "Veuillez contacter l'administration.\n\n— SIGEP / ESATIC");
        }
    }

    @Async
    public void notifierDecisionRattrapage(String email, String prenom, String matiere,
                                           String quand, boolean accepte) {
        if (accepte) {
            envoyer(email, "SIGEP — Rattrapage accepté",
                    "Bonjour " + prenom + ",\n\nVotre demande de rattrapage (" + matiere + ") a été ACCEPTÉE "
                    + "pour le " + quand + ".\n\n— SIGEP / ESATIC");
        } else {
            envoyer(email, "SIGEP — Rattrapage refusé",
                    "Bonjour " + prenom + ",\n\nVotre demande de rattrapage (" + matiere + ") a été refusée.\n\n— SIGEP / ESATIC");
        }
    }

    @Async
    public void notifierSeancesNonEmargees(String email, String prenom, List<String> lignes) {
        if (email == null || lignes == null || lignes.isEmpty()) return;
        String corps = "Bonjour " + prenom + ",\n\nVous avez " + lignes.size()
                + " séance(s) non émargée(s) aujourd'hui :\n"
                + String.join("\n", lignes)
                + "\n\nPensez à régulariser votre émargement.\n\n— SIGEP / ESATIC";
        envoyer(email, "SIGEP — Séances non émargées", corps);
    }

    private void envoyer(String destinataire, String sujet, String corps) {
        if (destinataire == null || destinataire.isBlank()) return;
        if (!enabled) {
            log.info("[MAIL désactivé] à={} | sujet={}", destinataire, sujet);
            return;
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("Envoi e-mail impossible : aucun JavaMailSender configuré (spring.mail.host manquant)");
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
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
