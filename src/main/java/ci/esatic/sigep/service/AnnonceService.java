package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Annonce;
import ci.esatic.sigep.entity.TypeAnnonce;
import ci.esatic.sigep.repository.AnnonceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Annonces de la plateforme : le super administrateur les publie, chaque console d'établissement
 * affiche celle en cours dans son en-tête.
 *
 * <p>Le bandeau figure sur toutes les pages des consoles : il ne doit pas coûter une requête par
 * page. Les annonces non terminées sont donc gardées en mémoire et relues toutes les
 * {@link #FRAICHEUR} au plus ; une publication ou une clôture vide aussitôt la copie de
 * l'instance qui la reçoit, les autres la voient au plus tard à la relecture suivante.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnnonceService {

    public static final int LONGUEUR_MAX = 300;
    static final Duration FRAICHEUR = Duration.ofSeconds(30);

    /** Ce que la console affiche : jamais l'entité, qui ne doit pas sortir de la session. */
    public record AnnonceVisible(Long id, TypeAnnonce type, String message, LocalDateTime fin) {
        public boolean masquable() { return type.masquable(); }
    }

    private record Copie(List<Annonce> annonces, long lueLe) {}

    private final AnnonceRepository repository;
    private volatile Copie copie;

    /**
     * L'annonce à afficher maintenant, s'il y en a une. Une maintenance passe avant le reste,
     * puis la plus récemment commencée.
     */
    public Optional<AnnonceVisible> enCours() {
        LocalDateTime maintenant = LocalDateTime.now();
        return annoncesNonTerminees().stream()
                .filter(a -> !a.getDebut().isAfter(maintenant))
                .filter(a -> a.getFin() == null || a.getFin().isAfter(maintenant))
                .min(Comparator.comparing((Annonce a) -> a.getType() != TypeAnnonce.MAINTENANCE)
                        .thenComparing(Annonce::getDebut, Comparator.reverseOrder()))
                .map(a -> new AnnonceVisible(a.getId(), a.getType(), a.getMessage(), a.getFin()));
    }

    private List<Annonce> annoncesNonTerminees() {
        Copie c = copie;
        long maintenant = System.currentTimeMillis();
        if (c != null && maintenant - c.lueLe() < FRAICHEUR.toMillis()) {
            return c.annonces();
        }
        try {
            List<Annonce> lues = List.copyOf(repository.findByFinIsNullOrFinAfter(LocalDateTime.now()));
            copie = new Copie(lues, maintenant);
            return lues;
        } catch (RuntimeException e) {
            // Un bandeau manquant ne doit jamais empêcher la console de s'afficher.
            log.warn("Annonces illisibles, le bandeau est omis : {}", e.getMessage());
            return c != null ? c.annonces() : List.of();
        }
    }

    /** Oublie la copie en mémoire : la prochaine lecture repasse par la base. */
    public void invalider() {
        copie = null;
    }

    /** Les dernières annonces publiées, pour l'espace plateforme. */
    @Transactional(readOnly = true)
    public List<Annonce> historique() {
        return repository.findTop30ByOrderByCreeeLeDesc();
    }

    /**
     * Publie une annonce. Sans début, elle commence tout de suite ; sans fin, elle reste affichée
     * jusqu'à sa clôture.
     *
     * @throws IllegalArgumentException si le message ou les dates ne conviennent pas
     */
    @Transactional
    public Annonce publier(TypeAnnonce type, String message, LocalDateTime debut, LocalDateTime fin, String auteur) {
        if (type == null) throw new IllegalArgumentException("Choisissez le type de l'annonce.");
        String texte = message == null ? "" : message.strip().replaceAll("\\s+", " ");
        if (texte.isEmpty()) throw new IllegalArgumentException("Le message de l'annonce est vide.");
        if (texte.length() > LONGUEUR_MAX) {
            throw new IllegalArgumentException("Le message dépasse " + LONGUEUR_MAX + " caractères.");
        }
        LocalDateTime maintenant = LocalDateTime.now();
        LocalDateTime commence = debut != null ? debut : maintenant;
        if (fin != null && !fin.isAfter(commence)) {
            throw new IllegalArgumentException("La fin doit venir après le début.");
        }
        if (fin != null && !fin.isAfter(maintenant)) {
            throw new IllegalArgumentException("La fin est déjà passée : l'annonce ne serait jamais vue.");
        }
        Annonce annonce = repository.save(Annonce.builder()
                .type(type).message(texte).debut(commence).fin(fin).auteur(auteur)
                .creeeLe(maintenant).build());
        copie = null;
        return annonce;
    }

    /** Retire l'annonce des consoles dès maintenant. Une annonce déjà terminée reste telle quelle. */
    @Transactional
    public void clore(Long id) {
        Annonce annonce = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Annonce introuvable."));
        LocalDateTime maintenant = LocalDateTime.now();
        if (annonce.getFin() != null && !annonce.getFin().isAfter(maintenant)) return;
        if (annonce.getDebut().isAfter(maintenant)) annonce.setDebut(maintenant);
        annonce.setFin(maintenant);
        copie = null;
    }
}
