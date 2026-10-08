package ci.esatic.sigep.security.sso;

import ci.esatic.sigep.entity.SeveriteEvenement;
import ci.esatic.sigep.entity.TypeEvenement;
import ci.esatic.sigep.security.ClientIpResolver;
import ci.esatic.sigep.service.JournalSecuriteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Ce qui se passe quand l'échange avec le fournisseur d'identité tourne court.
 *
 * <p>Deux familles de causes, et elles ne se ressemblent pas. L'ordinaire : le visiteur a refusé
 * de donner son accord, sa session d'autorisation a expiré pendant qu'il cherchait son téléphone,
 * le fournisseur était en panne. L'inquiétant : un jeton d'état qui ne correspond à rien, un code
 * d'autorisation présenté deux fois, une signature invalide — autant de signes qu'on essaie de
 * rejouer ou de forger un retour d'authentification.
 *
 * <p>Les deux reçoivent le même traitement visible — retour à la page de connexion — parce que
 * distinguer les messages renseignerait précisément celui qui cherche à forger. La différence se
 * fait dans le journal de sécurité, qui est l'endroit où elle sert.
 *
 * <p>Sans ce gestionnaire, Spring afficherait sa propre page d'erreur, en anglais, avec le code
 * technique du fournisseur : incompréhensible pour le visiteur, et bavard pour qui sonde.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EchecSsoHandler implements AuthenticationFailureHandler {

    private final JournalSecuriteService journalSecurite;
    private final ClientIpResolver resolveurIp;

    /** Codes d'erreur OAuth2 qui désignent un refus délibéré du visiteur, non un incident. */
    private static final java.util.Set<String> REFUS_DU_VISITEUR =
            java.util.Set.of("access_denied", "consent_required", "login_required",
                    "interaction_required");

    @Override
    public void onAuthenticationFailure(HttpServletRequest requete, HttpServletResponse reponse,
                                        AuthenticationException exception) throws IOException {
        String code = exception instanceof OAuth2AuthenticationException oauth
                && oauth.getError() != null ? oauth.getError().getErrorCode() : "echec";
        boolean refusVisiteur = REFUS_DU_VISITEUR.contains(code);

        journalSecurite.enregistrer(TypeEvenement.CONNEXION_SSO_REFUSEE,
                // Un visiteur qui annule n'est pas un incident ; un état invalide en est un.
                refusVisiteur ? SeveriteEvenement.INFO : SeveriteEvenement.ALERTE,
                resolveurIp.resolve(requete), null, requete.getRequestURI(),
                "Echange SSO interrompu (" + code + ")");

        if (!refusVisiteur) {
            log.warn("SSO : échange interrompu sur {} — code « {} »", requete.getRequestURI(), code);
        }

        SecurityContextHolder.clearContext();
        var session = requete.getSession(false);
        // Pas si quelqu'un travaillait dedans : la même session de navigateur peut déjà porter
        // un administrateur connecté par mot de passe, qui vient simplement d'ouvrir un lien SSO
        // périmé dans un second onglet. Le jeter dehors pour un échec qui ne le concerne pas
        // serait la pire façon de traiter une panne du fournisseur.
        if (session != null && !ConnexionSsoHandler.porteUnCompteApplicatif(session)) {
            session.invalidate();
        }
        reponse.sendRedirect("/admin-login?sso_error="
                + (refusVisiteur ? "annule" : "echange"));
    }
}
