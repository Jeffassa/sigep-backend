package ci.esatic.sigep.annuaire;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collecte les écoles et universités des pays de l'UEMOA depuis OpenStreetMap, pays par pays, et
 * les range dans l'annuaire de SIGEP. La source est l'extraction « Education Facilities » que
 * l'équipe humanitaire d'OpenStreetMap (HOT) publie chaque mois par pays sur HDX : un fichier
 * prévu pour le téléchargement, contrairement au service Overpass qui refuse les collectes
 * automatiques. Les recherches faites à l'inscription
 * interrogent ensuite cette copie : elles ne sortent jamais de SIGEP.
 *
 * <p>La collecte tourne chaque mois, au premier démarrage si l'annuaire est vide, et à la demande
 * depuis la plateforme. Les données d'OpenStreetMap sont sous licence ODbL : la source est citée
 * là où l'annuaire est affiché.
 */
@Service
@Slf4j
public class AnnuaireCollecteService {

    /** Extraction mensuelle par pays (code ISO à trois lettres, en majuscules puis en minuscules). */
    private static final String FICHIER =
            "%s/ISO3/%s/education_facilities/hotosm_%s_education_facilities_osm_geojson.zip";

    private final AnnuaireEtablissementRepository etablissements;
    private final AnnuaireCollecteRepository collectes;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final AtomicBoolean enCours = new AtomicBoolean(false);

    @Value("${app.annuaire.source-url:https://production-raw-data-api.s3.amazonaws.com}")
    private String sourceUrl;

    @Value("${app.annuaire.collecte-active:true}")
    private boolean active;

    public AnnuaireCollecteService(AnnuaireEtablissementRepository etablissements, AnnuaireCollecteRepository collectes,
                                   JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper) {
        this.etablissements = etablissements;
        this.collectes = collectes;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.mapper = mapper;
    }

    public boolean estEnCours() {
        return enCours.get();
    }

    /** Annuaire vide au démarrage : la première collecte part aussitôt, en arrière-plan. */
    @EventListener(ApplicationReadyEvent.class)
    public void auDemarrage() {
        if (active && etablissements.count() == 0) lancerEnArrierePlan();
    }

    /** Une fois par mois, la nuit du 1er : OpenStreetMap s'enrichit, l'annuaire suit. */
    @Scheduled(cron = "${app.annuaire.cron:0 30 3 1 * *}")
    @SchedulerLock(name = "collecteAnnuaire", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void collecteMensuelle() {
        if (active) toutCollecterSiLibre();
    }

    /** Lance une collecte complète sans bloquer l'appelant ; faux si une collecte tourne déjà. */
    public boolean lancerEnArrierePlan() {
        if (!enCours.compareAndSet(false, true)) return false;
        Thread.ofVirtual().name("collecte-annuaire").start(() -> {
            try {
                toutCollecter();
            } finally {
                enCours.set(false);
            }
        });
        return true;
    }

    private void toutCollecterSiLibre() {
        if (!enCours.compareAndSet(false, true)) return;
        try {
            toutCollecter();
        } finally {
            enCours.set(false);
        }
    }

    private void toutCollecter() {
        for (PaysUemoa pays : PaysUemoa.ouverts()) {
            collecterPays(pays);
            try {
                Thread.sleep(2_000);   // un pays après l'autre, sans presser la source
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Collecte un pays et remplace ses fiches ; une erreur laisse l'ancienne liste en place. */
    public void collecterPays(PaysUemoa pays) {
        LocalDateTime maintenant = LocalDateTime.now();
        try {
            String url = FICHIER.formatted(sourceUrl, pays.getIso3(), pays.getIso3().toLowerCase(java.util.Locale.ROOT));
            HttpRequest requete = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(3))
                    .header("User-Agent", "SIGEP-annuaire/1.0 (+https://sigep.store)")
                    .GET()
                    .build();
            HttpResponse<byte[]> reponse = http.send(requete, HttpResponse.BodyHandlers.ofByteArray());
            if (reponse.statusCode() != 200) {
                throw new IllegalStateException("La source a répondu " + reponse.statusCode());
            }
            byte[] geoJson = LecteurEducationFacilities.extraireGeoJson(reponse.body());
            List<AnnuaireEtablissement> fiches = LecteurEducationFacilities.lire(geoJson, pays, mapper, maintenant);
            // Une extraction presque vide serait une panne de la source : on garde l'ancienne liste.
            long avant = etablissements.countByPaysCode(pays.getCode());
            if (fiches.isEmpty() || (avant > 50 && fiches.size() < avant / 2)) {
                throw new IllegalStateException("Extraction anormalement réduite (" + fiches.size() + " fiches)");
            }
            remplacer(pays, fiches);
            noter(pays, maintenant, fiches.size(), null);
            log.info("Annuaire : {} établissements collectés pour {}", fiches.size(), pays.getNom());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            noter(pays, maintenant, null, "Collecte interrompue");
        } catch (Exception e) {
            log.warn("Annuaire : collecte impossible pour {} : {}", pays.getNom(), e.getMessage());
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            noter(pays, maintenant, null, message.length() > 300 ? message.substring(0, 300) : message);
        }
    }

    /** Remplace les fiches d'un pays d'un seul coup : les recherches voient l'ancienne liste jusqu'au bout. */
    void remplacer(PaysUemoa pays, List<AnnuaireEtablissement> fiches) {
        transaction.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM annuaire_etablissements WHERE pays_code = ? AND source = 'OSM'", pays.getCode());
            jdbc.batchUpdate("INSERT INTO annuaire_etablissements (nom, nom_recherche, type, ville, pays_code, source,"
                            + " source_id, latitude, longitude, date_collecte) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    fiches, 500, (ps, f) -> {
                        ps.setString(1, f.getNom());
                        ps.setString(2, f.getNomRecherche());
                        ps.setString(3, f.getType().name());
                        ps.setString(4, f.getVille());
                        ps.setString(5, f.getPaysCode());
                        ps.setString(6, f.getSource());
                        ps.setString(7, f.getSourceId());
                        if (f.getLatitude() == null) ps.setNull(8, Types.DOUBLE); else ps.setDouble(8, f.getLatitude());
                        if (f.getLongitude() == null) ps.setNull(9, Types.DOUBLE); else ps.setDouble(9, f.getLongitude());
                        ps.setTimestamp(10, Timestamp.valueOf(f.getDateCollecte()));
                    });
        });
    }

    private void noter(PaysUemoa pays, LocalDateTime date, Integer nombre, String erreur) {
        AnnuaireCollecte c = collectes.findById(pays.getCode()).orElseGet(() -> AnnuaireCollecte.builder()
                .paysCode(pays.getCode()).build());
        c.setDateCollecte(date);
        if (nombre != null) c.setNombre(nombre);
        c.setErreur(erreur);
        collectes.save(c);
    }
}
