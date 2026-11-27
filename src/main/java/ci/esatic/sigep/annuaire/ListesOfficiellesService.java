package ci.esatic.sigep.annuaire;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Recopie dans SIGEP les listes des établissements autorisés par l'État en Côte d'Ivoire :
 * <ul>
 *   <li>les universités et grandes écoles privées autorisées, publiées par le MESRS sur son
 *       portail d'orientation des bacheliers ;</li>
 *   <li>les collèges et lycées, avec leur code, d'après le classement du BEPC que publie la
 *       Direction des examens et concours du MENA : seul un établissement reconnu présente des
 *       candidats. Le classement le plus récent est cherché seul, session après session ;</li>
 *   <li>les universités et grandes écoles publiques, saisies par SIGEP.</li>
 * </ul>
 * Les recherches et rapprochements interrogent ensuite cette copie : ils ne sortent jamais de
 * SIGEP. L'import tourne chaque mois, au premier démarrage si les listes sont vides, et à la
 * demande depuis la plateforme. Une liste vide ou fortement réduite ne remplace pas l'ancienne.
 */
@Service
@Slf4j
public class ListesOfficiellesService {

    private static final int TAILLE_MAX_PDF = 40 * 1024 * 1024;

    private final EtablissementOfficielRepository officiels;
    private final ImportOfficielRepository imports;
    private final ReconnaissanceService reconnaissance;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final AtomicBoolean enCours = new AtomicBoolean(false);

    @Value("${app.officiels.mesrs-url:https://bac.mesrs-ci.net}")
    private String mesrsUrl;

    /**
     * Le classement du BEPC paraît chaque été sous un nom fixe, l'année changeant, dans l'un de ces
     * dossiers (2024 dans {@code docs}, 2025 dans {@code data/json}) : le plus récent est cherché seul.
     */
    private static final String CLASSEMENT_BEPC = "BEPC_SESSION_%d_CLASSEMENT_DES_ETABLISSEMENTS.pdf";
    private static final List<String> DOSSIERS_BEPC = List.of("public/data/json", "public/docs", "public/data");
    private static final int SESSIONS_EN_ARRIERE = 3;

    @Value("${app.officiels.mena-site:https://statistiques.men-deco.org}")
    private String menaSite;

    /** Adresse imposée du classement, si un jour son nom change ; vide : recherche automatique. */
    @Value("${app.officiels.mena-url:}")
    private String menaUrl;

    /** Le dernier classement trouvé, pour l'afficher sur la plateforme. */
    private volatile String classementMena;

    @Value("${app.officiels.import-actif:true}")
    private boolean active;

    public ListesOfficiellesService(EtablissementOfficielRepository officiels, ImportOfficielRepository imports,
                                    ReconnaissanceService reconnaissance, JdbcTemplate jdbc,
                                    TransactionTemplate transaction) {
        this.officiels = officiels;
        this.imports = imports;
        this.reconnaissance = reconnaissance;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public boolean estEnCours() {
        return enCours.get();
    }

    public String getClassementMena() {
        return classementMena;
    }

    /**
     * Le classement du BEPC le plus récent : de la session de l'année en cours aux trois
     * précédentes, le premier PDF publié l'emporte.
     */
    String trouverClassementMena() throws Exception {
        if (menaUrl != null && !menaUrl.isBlank()) return menaUrl;
        int annee = java.time.Year.now().getValue();
        for (int session = annee; session >= annee - SESSIONS_EN_ARRIERE; session--) {
            for (String dossier : DOSSIERS_BEPC) {
                String url = menaSite + "/" + dossier + "/" + CLASSEMENT_BEPC.formatted(session);
                if (estUnPdf(url)) return url;
            }
        }
        throw new IllegalStateException("Aucun classement du BEPC trouvé depuis la session " + (annee - SESSIONS_EN_ARRIERE));
    }

    private boolean estUnPdf(String url) throws InterruptedException {
        try {
            HttpRequest requete = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "SIGEP-listes-officielles/1.0 (+https://sigep.store)")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> reponse = http.send(requete, HttpResponse.BodyHandlers.discarding());
            return reponse.statusCode() == 200 && reponse.headers().firstValue("Content-Type")
                    .map(t -> t.toLowerCase(java.util.Locale.ROOT).contains("pdf")).orElse(false);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return false;   // adresse absente ou injoignable : on essaie la suivante
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void auDemarrage() {
        if (active && officiels.count() == 0) lancerEnArrierePlan();
    }

    /** Le 2 de chaque mois, la nuit : le lendemain de la collecte de l'annuaire. */
    @Scheduled(cron = "${app.officiels.cron:0 30 3 2 * *}")
    @SchedulerLock(name = "importListesOfficielles", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    public void importMensuel() {
        if (active && enCours.compareAndSet(false, true)) {
            try {
                toutImporter();
            } finally {
                enCours.set(false);
            }
        }
    }

    /** Lance un import complet sans bloquer l'appelant ; faux si un import tourne déjà. */
    public boolean lancerEnArrierePlan() {
        if (!enCours.compareAndSet(false, true)) return false;
        Thread.ofVirtual().name("import-listes-officielles").start(() -> {
            try {
                toutImporter();
            } finally {
                enCours.set(false);
            }
        });
        return true;
    }

    void toutImporter() {
        for (SourceOfficielle source : SourceOfficielle.values()) importer(source);
        int reconnus = reconnaissance.rapprocherTous();
        log.info("Listes officielles : {} établissements clients reconnus", reconnus);
    }

    /** Importe une liste et remplace la précédente ; une erreur laisse l'ancienne en place. */
    public void importer(SourceOfficielle source) {
        LocalDateTime maintenant = LocalDateTime.now();
        try {
            List<EtablissementOfficiel> liste = switch (source) {
                case MESRS_UNIVERSITES -> LecteurListesOfficielles.lireMesrs(page(mesrsUrl + "/offres/ets-prives"),
                        source, CategorieOfficielle.UNIVERSITE_PRIVEE, maintenant);
                case MESRS_GRANDES_ECOLES -> LecteurListesOfficielles.lireMesrs(page(mesrsUrl + "/offres/grdes-ecoles"),
                        source, CategorieOfficielle.GRANDE_ECOLE_PRIVEE, maintenant);
                case MENA_BEPC -> {
                    String url = trouverClassementMena();
                    List<EtablissementOfficiel> lus = LecteurListesOfficielles.lireMena(fichier(url), maintenant);
                    classementMena = url;
                    yield lus;
                }
                case SIGEP_PUBLIC -> {
                    try (InputStream csv = new ClassPathResource("officiels/superieur-public.csv").getInputStream()) {
                        yield LecteurListesOfficielles.lireSaisie(csv, maintenant);
                    }
                }
            };
            long avant = officiels.countBySource(source);
            if (liste.isEmpty() || (avant > 20 && liste.size() < avant / 2)) {
                throw new IllegalStateException("Liste anormalement réduite (" + liste.size() + " établissements)");
            }
            remplacer(source, liste);
            noter(source, maintenant, liste.size(), null);
            log.info("Listes officielles : {} établissements pour {}", liste.size(), source);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            noter(source, maintenant, null, "Import interrompu");
        } catch (Exception e) {
            log.warn("Listes officielles : import impossible pour {} : {}", source, e.getMessage());
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            noter(source, maintenant, null, message.length() > 300 ? message.substring(0, 300) : message);
        }
    }

    private HttpResponse<byte[]> telecharger(String url) throws Exception {
        HttpRequest requete = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("User-Agent", "SIGEP-listes-officielles/1.0 (+https://sigep.store)")
                .GET()
                .build();
        HttpResponse<byte[]> reponse = http.send(requete, HttpResponse.BodyHandlers.ofByteArray());
        if (reponse.statusCode() != 200) throw new IllegalStateException("La source a répondu " + reponse.statusCode());
        return reponse;
    }

    private String page(String url) throws Exception {
        return new String(telecharger(url).body(), StandardCharsets.UTF_8);
    }

    private byte[] fichier(String url) throws Exception {
        byte[] contenu = telecharger(url).body();
        if (contenu.length > TAILLE_MAX_PDF) throw new IllegalStateException("Fichier trop volumineux");
        return contenu;
    }

    /** Remplace une liste d'un seul coup : les recherches voient l'ancienne jusqu'au bout. */
    void remplacer(SourceOfficielle source, List<EtablissementOfficiel> liste) {
        transaction.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM etablissements_officiels WHERE source = ?", source.name());
            jdbc.batchUpdate("INSERT INTO etablissements_officiels (source, categorie, code_officiel, nom, nom_recherche,"
                            + " nom_cle, ville, commune, date_import) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    liste, 500, (ps, o) -> {
                        ps.setString(1, o.getSource().name());
                        ps.setString(2, o.getCategorie().name());
                        ps.setString(3, o.getCodeOfficiel());
                        ps.setString(4, o.getNom());
                        ps.setString(5, o.getNomRecherche());
                        ps.setString(6, o.getNomCle());
                        ps.setString(7, o.getVille());
                        ps.setString(8, o.getCommune());
                        ps.setTimestamp(9, Timestamp.valueOf(o.getDateImport()));
                    });
        });
    }

    private void noter(SourceOfficielle source, LocalDateTime date, Integer nombre, String erreur) {
        ImportOfficiel i = imports.findById(source).orElseGet(() -> ImportOfficiel.builder().source(source).build());
        i.setDateImport(date);
        if (nombre != null) i.setNombre(nombre);
        i.setErreur(erreur);
        imports.save(i);
    }
}
