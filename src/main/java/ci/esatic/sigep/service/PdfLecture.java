package ci.esatic.sigep.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;

/**
 * Ouverture d'un PDF venu de l'extérieur (emploi du temps importé, classement publié par le
 * ministère) avec une mémoire de travail bornée : un fichier piégé qui se décompresse à l'excès
 * échoue proprement au lieu d'épuiser la mémoire du serveur.
 */
public final class PdfLecture {

    /** Mémoire de travail allouée aux flux décodés d'un document. */
    public static final long PLAFOND_MEMOIRE = 128L * 1024 * 1024;

    private PdfLecture() {
    }

    public static PDDocument ouvrir(byte[] octets) throws IOException {
        return Loader.loadPDF(octets, "", null, null,
                MemoryUsageSetting.setupMainMemoryOnly(PLAFOND_MEMOIRE).streamCache);
    }
}
