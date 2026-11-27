package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.ApplicationMobile;
import ci.esatic.sigep.entity.ApplicationMobileMorceau;
import ci.esatic.sigep.repository.ApplicationMobileRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * L'application Android, servie depuis sigep.store.
 *
 * <p>La plateforme dépose l'APK ; il est rangé dans la base, découpé en morceaux d'un mégaoctet,
 * et chaque établissement peut en envoyer le lien à ses enseignants. Aucun service extérieur :
 * le fichier reste chez nous, et un nouveau dépôt remplace le précédent.
 */
@Service
@RequiredArgsConstructor
public class ApplicationMobileService {

    /** Bien au-delà de l'APK actuel (Android 10+, ARM 32 et 64 bits) ; borne un dépôt aberrant. */
    public static final long TAILLE_MAX = 100L * 1024 * 1024;

    private static final Pattern VERSION = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    /** Un APK est une archive ZIP : il commence par « PK\3\4 ». */
    private static final byte[] DEBUT_ZIP = {0x50, 0x4B, 0x03, 0x04};

    private final ApplicationMobileRepository repository;
    private final EntityManager entityManager;

    public Optional<ApplicationMobile> courante() {
        return repository.findFirstByOrderByDateDepotDescIdDesc();
    }

    /**
     * Range l'APK lu dans {@code flux}, morceau par morceau, à la place de la version précédente.
     * Tout est annulé si le fichier est refusé en cours de route.
     *
     * @throws IllegalArgumentException version illisible, fichier vide, trop lourd ou qui n'est pas un APK
     */
    @Transactional(rollbackFor = Exception.class)
    public ApplicationMobile deposer(InputStream flux, String version, String auteur) throws IOException {
        String v = version == null ? "" : version.trim();
        if (!VERSION.matcher(v).matches()) {
            throw new IllegalArgumentException("Version illisible : saisissez-la sous la forme 1.2.1.");
        }

        repository.supprimerMorceaux();
        repository.supprimerVersions();
        ApplicationMobile app = repository.save(ApplicationMobile.builder()
                .version(v).taille(0).sha256("").morceaux(0)
                .dateDepot(LocalDateTime.now()).deposePar(auteur).build());

        MessageDigest empreinte = sha256();
        long taille = 0;
        int numero = 0;
        while (true) {
            byte[] lu = flux.readNBytes(ApplicationMobileMorceau.TAILLE);
            if (lu.length == 0) break;
            if (numero == 0 && !commencePar(lu, DEBUT_ZIP)) {
                throw new IllegalArgumentException("Ce fichier n'est pas un APK.");
            }
            taille += lu.length;
            if (taille > TAILLE_MAX) {
                throw new IllegalArgumentException("Fichier trop lourd (plus de "
                        + TAILLE_MAX / (1024 * 1024) + " Mo).");
            }
            empreinte.update(lu);
            ApplicationMobileMorceau morceau = new ApplicationMobileMorceau(app.getId(), numero++, lu);
            // Écrit puis oublié : le fichier ne s'accumule pas dans le contexte de persistance.
            entityManager.persist(morceau);
            entityManager.flush();
            entityManager.detach(morceau);
        }
        if (taille == 0) throw new IllegalArgumentException("Le fichier est vide.");

        app.setTaille(taille);
        app.setMorceaux(numero);
        app.setSha256(HexFormat.of().formatHex(empreinte.digest()));
        return repository.save(app);
    }

    /** Écrit l'APK dans {@code sortie}, un morceau à la fois. */
    public void ecrire(ApplicationMobile app, OutputStream sortie) throws IOException {
        for (int i = 0; i < app.getMorceaux(); i++) {
            byte[] morceau = repository.morceau(app.getId(), i);
            if (morceau == null) throw new IOException("Morceau " + i + " manquant");
            sortie.write(morceau);
        }
        sortie.flush();
    }

    /** Nom du fichier tel que l'enseignant le reçoit. */
    public static String nomFichier(ApplicationMobile app) {
        return "SIGEP-" + app.getVersion() + ".apk";
    }

    private static boolean commencePar(byte[] donnees, byte[] debut) {
        if (donnees.length < debut.length) return false;
        for (int i = 0; i < debut.length; i++) if (donnees[i] != debut[i]) return false;
        return true;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
