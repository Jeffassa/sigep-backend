package ci.esatic.sigep.security;

import ci.esatic.sigep.entity.CleAgent;
import ci.esatic.sigep.entity.ERole;
import ci.esatic.sigep.entity.StatutEtablissement;
import ci.esatic.sigep.entity.User;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.repository.UserRepository;
import ci.esatic.sigep.service.AbonnementService;
import ci.esatic.sigep.service.CleAgentService;
import ci.esatic.sigep.service.JournalAgentService;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authentifie un appel de l'agent à partir de sa clé, et le journalise.
 *
 * <p>La clé est attendue dans l'en-tête {@code X-Agent-Key}. Elle désigne un administrateur ;
 * l'identité posée ici EST cet administrateur, relu en base à chaque appel. L'agent hérite donc
 * de tout ce qui le borne — son établissement, son profil, l'isolation multi-tenant — et perd
 * l'accès dès que lui le perd : compte désactivé, profil abaissé, établissement suspendu, plan
 * qui ne comprend plus l'agent.
 *
 * <p>Chaque appel authentifié laisse une ligne dans le journal de l'agent, qu'il réussisse, échoue
 * ou ne soit qu'une simulation.
 */
@Component
@RequiredArgsConstructor
public class AgentAuthFilter extends OncePerRequestFilter {

    public static final String ENTETE = "X-Agent-Key";
    public static final String PREFIXE_CHEMIN = "/api/agent/";

    /** Attributs de requête qu'un contrôleur d'agent renseigne pour le journal. */
    public static final String ATTR_RESUME = "sigep.agent.resume";
    public static final String ATTR_SIMULATION = "sigep.agent.simulation";

    private final CleAgentService cleAgentService;
    private final UserRepository userRepository;
    private final EtablissementRepository etablissementRepository;
    private final PlanService planService;
    private final AbonnementService abonnementService;
    private final JournalAgentService journalAgent;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIXE_CHEMIN);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse,
                                    FilterChain chaine) throws ServletException, IOException {
        String cle = requete.getHeader(ENTETE);
        if (cle == null || cle.isBlank()) {
            refuser(reponse, 401, "Clé d'agent manquante. Ajoutez l'en-tête " + ENTETE + ".");
            return;
        }
        var trouvee = cleAgentService.reconnaitre(cle);
        if (trouvee.isEmpty()) {
            // Même réponse pour une clé inconnue et une clé révoquée.
            refuser(reponse, 401, "Clé d'agent inconnue ou révoquée.");
            return;
        }
        CleAgent cleAgent = trouvee.get();

        User administrateur = userRepository.findById(cleAgent.getUserId()).orElse(null);
        String refus = motifDeRefus(cleAgent, administrateur);
        if (refus != null) {
            journaliser(requete, cleAgent, 403, refus);
            refuser(reponse, 403, refus);
            return;
        }

        var auth = new UsernamePasswordAuthenticationToken(administrateur, null, administrateur.getAuthorities());
        auth.setDetails(new AgentIdentite(cleAgent.getId(), cleAgent.getLibelle()));
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            chaine.doFilter(requete, reponse);
        } finally {
            // L'identité ne doit pas survivre à la requête : le thread est réutilisé.
            SecurityContextHolder.clearContext();
            Object resume = requete.getAttribute(ATTR_RESUME);
            journaliser(requete, cleAgent, reponse.getStatus(), resume == null ? null : resume.toString());
        }
    }

    /** Raison pour laquelle cette clé ne peut pas agir maintenant, ou null si elle le peut. */
    private String motifDeRefus(CleAgent cleAgent, User admin) {
        if (admin == null || !admin.isEnabled()) {
            return "Le compte lié à cet agent n'est plus actif.";
        }
        boolean administrateur = admin.getRoles().stream().anyMatch(r -> r.getName() == ERole.ROLE_ADMIN);
        if (!administrateur) {
            return "Le compte lié à cet agent n'est plus administrateur de l'établissement.";
        }
        if (admin.getEtablissement() == null
                || !admin.getEtablissement().getId().equals(cleAgent.getEtablissementId())) {
            return "Le compte lié à cet agent n'appartient plus à cet établissement.";
        }
        var etab = etablissementRepository.findById(cleAgent.getEtablissementId()).orElse(null);
        if (etab == null || etab.getStatut() != StatutEtablissement.VALIDE) {
            return "Établissement introuvable ou non validé.";
        }
        if (abonnementService.accesSuspendu(etab)) {
            return "L'accès de cet établissement est suspendu.";
        }
        if (!planService.estDisponible(etab, Feature.AGENT)) {
            return "L'agent est compris dans le plan Entreprise. Votre plan actuel ne l'inclut pas.";
        }
        return null;
    }

    private void journaliser(HttpServletRequest requete, CleAgent cle, int statut, String resume) {
        journalAgent.enregistrer(cle.getEtablissementId(), cle.getId(), cle.getUserId(),
                requete.getMethod(), requete.getRequestURI(), statut,
                Boolean.TRUE.equals(requete.getAttribute(ATTR_SIMULATION)), resume);
    }

    private void refuser(HttpServletResponse reponse, int code, String message) throws IOException {
        reponse.setStatus(code);
        reponse.setContentType("application/json;charset=UTF-8");
        reponse.getWriter().write("{\"success\":false,\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}
