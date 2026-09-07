package ci.esatic.sigep.controller.web;

import ci.esatic.sigep.service.MailService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/** Deuxième étape de connexion pour les administrateurs établissement et plateforme. */
@Controller
@RequiredArgsConstructor
@Slf4j
public class AdminOtpController {

    public static final String VERIFIED = "SIGEP_ADMIN_OTP_VERIFIED";
    private static final String HASH = "SIGEP_ADMIN_OTP_HASH";
    private static final String EXPIRES = "SIGEP_ADMIN_OTP_EXPIRES";
    private static final String ATTEMPTS = "SIGEP_ADMIN_OTP_ATTEMPTS";
    private static final String TARGET = "SIGEP_ADMIN_OTP_TARGET";
    private static final String RESENDS = "SIGEP_ADMIN_OTP_RESENDS";
    private static final int MAX_ATTEMPTS = 5;
    /** Un courriel est une ressource : sans borne, la page de renvoi en deviendrait un robinet. */
    private static final int MAX_RESENDS = 3;
    private static final long TTL_SECONDS = 300;

    private final MailService mailService;
    private final ci.esatic.sigep.service.JournalSecuriteService journalSecurite;
    private final ci.esatic.sigep.security.ClientIpResolver resolveurIp;

    /**
     * Empreinte SHA-256 du code de secours, en hexadécimal.
     *
     * <p>Le second facteur repose sur un courriel — donc sur un service tiers. Quand cet envoi
     * tombe en panne, l'administrateur possède toujours son mot de passe mais ne peut plus entrer :
     * sa clé fonctionne, le portier est absent. Ce code, conservé hors ligne par l'exploitant,
     * ouvre l'accès sans dépendre d'aucun envoi.
     *
     * <p>Seule l'EMPREINTE est configurée : le code lui-même n'existe nulle part sur le serveur,
     * et une fuite de la configuration ne le révélerait pas. Vide par défaut — sans valeur, la
     * fonction n'existe pas. Il reste un SECOND facteur : le mot de passe est déjà exigé pour
     * atteindre cette page, et le quota de tentatives s'applique aussi à lui.
     */
    @org.springframework.beans.factory.annotation.Value("${app.security.admin-otp.recovery-hash:}")
    private String empreinteCodeSecours;

    @GetMapping("/admin-otp")
    public String page(HttpSession session, Model model) {
        if (Boolean.TRUE.equals(session.getAttribute(VERIFIED))) return redirectAfterOtp(session);
        // Sans cette information, une variable d'environnement absente et un code erroné
        // produisaient le MÊME message : impossible de savoir lequel des deux on affronte.
        // Savoir qu'un filet existe n'aide personne à le franchir.
        model.addAttribute("secoursConfigure", secoursConfigure());
        return "admin/otp";
    }

    @PostMapping("/admin-otp")
    public String verifier(@RequestParam String code, HttpSession session, Model model,
                           Authentication authentication,
                           jakarta.servlet.http.HttpServletRequest requete) {
        if (authentication == null || !estAdmin(authentication)) return "redirect:/admin-login?error=true";
        Integer attempts = (Integer) session.getAttribute(ATTEMPTS);
        if (attempts == null) attempts = 0;
        String expected = (String) session.getAttribute(HASH);
        Long expires = (Long) session.getAttribute(EXPIRES);
        boolean quotaOuvert = attempts < MAX_ATTEMPTS;
        boolean codeRecuParMail = expected != null && expires != null
                && Instant.now().getEpochSecond() <= expires
                && constantTimeEquals(expected, hash(code));
        // Volontairement indépendant de la session : le cas d'usage est précisément celui où
        // aucun code n'a pu être préparé ni expédié.
        boolean codeDeSecours = secoursConfigure() && correspondAuSecours(code);
        boolean valide = quotaOuvert && (codeRecuParMail || codeDeSecours);
        if (valide) {
            if (codeDeSecours) {
                // Trace volontairement bruyante : un code de secours ne sert qu'en incident.
                // S'il apparaît sans incident connu, c'est le signal d'une compromission.
                log.warn("SECURITE : acces administrateur ouvert par CODE DE SECOURS pour {}",
                        authentication.getName());
                journal(requete, ci.esatic.sigep.entity.TypeEvenement.OTP_CODE_SECOURS,
                        ci.esatic.sigep.entity.SeveriteEvenement.CRITIQUE, authentication,
                        "Second facteur contourne par le code de secours");
            } else {
                journal(requete, ci.esatic.sigep.entity.TypeEvenement.CONNEXION_ADMIN_REUSSIE,
                        ci.esatic.sigep.entity.SeveriteEvenement.INFO, authentication,
                        "Connexion administrateur menee a son terme");
            }
            session.setAttribute(VERIFIED, true);
            session.removeAttribute(HASH);
            session.removeAttribute(EXPIRES);
            session.removeAttribute(ATTEMPTS);
            return redirectAfterOtp(session);
        }
        session.setAttribute(ATTEMPTS, attempts + 1);
        journal(requete, ci.esatic.sigep.entity.TypeEvenement.OTP_ECHOUE,
                // La derniere tentative fait basculer en CRITIQUE : cinq echecs d'affilee sur un
                // code a 6 chiffres ne ressemblent plus a une faute de frappe.
                attempts + 1 >= MAX_ATTEMPTS
                        ? ci.esatic.sigep.entity.SeveriteEvenement.CRITIQUE
                        : ci.esatic.sigep.entity.SeveriteEvenement.ALERTE,
                authentication, "Tentative " + (attempts + 1) + " sur " + MAX_ATTEMPTS);
        model.addAttribute("secoursConfigure", secoursConfigure());
        model.addAttribute("error", attempts + 1 >= MAX_ATTEMPTS
                ? "Trop de tentatives. Reconnectez-vous pour demander un nouveau code."
                : "Code invalide ou expiré.");
        return "admin/otp";
    }

    /**
     * Renvoi d'un code, borné par session.
     *
     * <p>Sans cette issue, un courriel perdu — filtré en indésirable, retardé par le fournisseur,
     * ou simplement jamais parti — enfermait l'administrateur dehors sans recours : la seule
     * consigne affichée était « reconnectez-vous », ce qui régénérait un code par le même canal
     * défaillant sans qu'il puisse rien tenter d'autre.
     */
    @PostMapping("/admin-otp/renvoyer")
    public String renvoyer(HttpSession session, Authentication authentication, Model model) {
        if (authentication == null || !estAdmin(authentication)) return "redirect:/admin-login?error=true";
        if (Boolean.TRUE.equals(session.getAttribute(VERIFIED))) return redirectAfterOtp(session);

        Integer envois = (Integer) session.getAttribute(RESENDS);
        if (envois == null) envois = 0;
        model.addAttribute("secoursConfigure", secoursConfigure());
        if (envois >= MAX_RESENDS) {
            model.addAttribute("error",
                    "Trop de renvois pour cette session. Reconnectez-vous pour repartir de zéro.");
            return "admin/otp";
        }
        if (authentication.getPrincipal() instanceof ci.esatic.sigep.entity.User u) {
            preparer(session, u.getEmail(), mailService,
                    authentication.getAuthorities().stream()
                            .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority())));
            // preparer() remet le compteur a zero : on le repositionne apres l'appel.
            session.setAttribute(RESENDS, envois + 1);
            model.addAttribute("info", "Un nouveau code vient d'être envoyé. Le précédent est annulé.");
        }
        return "admin/otp";
    }

    public static void preparer(HttpSession session, String email, MailService mailService,
                                boolean superAdmin) {
        String code = String.format("%06d", new SecureRandom().nextInt(1_000_000));
        session.setAttribute(HASH, hash(code));
        session.setAttribute(EXPIRES, Instant.now().getEpochSecond() + TTL_SECONDS);
        session.setAttribute(ATTEMPTS, 0);
        session.setAttribute(VERIFIED, false);
        session.setAttribute(TARGET, superAdmin ? "/plateforme" : "/admin/dashboard");
        session.setAttribute(RESENDS, 0);
        mailService.envoyerCodeOtpAdmin(email, code);
    }

    /** Un code de secours est-il configuré sur ce serveur ? */
    private boolean secoursConfigure() {
        return empreinteEpuree() != null;
    }

    /**
     * Empreinte configurée, débarrassée du bruit de saisie.
     *
     * <p>Les interfaces d'hébergeur invitent à coller la valeur entre guillemets, et un
     * copier-coller emporte volontiers un espace ou un retour à la ligne. Aucun de ces accidents
     * ne doit faire échouer silencieusement un filet de sécurité.
     */
    private String empreinteEpuree() {
        if (empreinteCodeSecours == null) return null;
        String v = empreinteCodeSecours.trim().replaceAll("^[\"']|[\"']$", "").trim().toLowerCase();
        return v.isEmpty() ? null : v;
    }

    /**
     * Le code saisi correspond-il au code de secours ?
     *
     * <p>Deux formes sont acceptées, et c'est délibéré : la chaîne EXACTE, et sa forme canonique
     * — majuscules, sans tiret ni espace. Un code long se retape un jour d'incident, souvent sur
     * un téléphone : refuser {@code a7k2 9qmr…} pour une question de casse ou d'espacement
     * transformerait le filet en piège, sans rien gagner en sécurité (l'entropie tient aux
     * caractères, pas à leur casse).
     *
     * <p>Les deux formes sont comparées à l'empreinte configurée, ce qui laisse le choix de
     * l'empreinte : celle de la chaîne exacte reste valable, celle de la forme canonique rend la
     * saisie tolérante.
     */
    private boolean correspondAuSecours(String code) {
        String attendu = empreinteEpuree();
        if (attendu == null) return false;
        String saisi = code == null ? "" : code.trim();
        if (saisi.isEmpty()) return false;
        String canonique = saisi.toUpperCase().replaceAll("[^A-Z0-9]", "");
        // Les deux comparaisons sont à temps constant ; l'opérateur | évite le court-circuit,
        // qui rendrait le temps de réponse dépendant de la forme saisie.
        return constantTimeEquals(attendu, hash(saisi)) | constantTimeEquals(attendu, hash(canonique));
    }

    /** Le code lui-meme n'est JAMAIS journalise : seul l'echec l'est. */
    private void journal(jakarta.servlet.http.HttpServletRequest requete,
                         ci.esatic.sigep.entity.TypeEvenement type,
                         ci.esatic.sigep.entity.SeveriteEvenement severite,
                         Authentication authentication, String details) {
        journalSecurite.enregistrer(type, severite,
                requete == null ? null : resolveurIp.resolve(requete),
                authentication == null ? null : authentication.getName(),
                "/admin-otp", details);
    }

    private boolean estAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

    private String redirectAfterOtp(HttpSession session) {
        String target = (String) session.getAttribute(TARGET);
        return "redirect:" + (target == null ? "/admin/dashboard" : target);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value.trim()).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Hash OTP indisponible", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}