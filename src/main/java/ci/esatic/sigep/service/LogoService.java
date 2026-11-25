package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.repository.EtablissementRepository;
import ci.esatic.sigep.tenant.plan.Feature;
import ci.esatic.sigep.tenant.plan.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;

/** Logo de l'établissement : règles partagées par la console et l'API d'agent. */
@Service
@RequiredArgsConstructor
public class LogoService {

    /** Au-delà, l'image n'est plus un logo : on refuse plutôt que de stocker n'importe quoi. */
    public static final long TAILLE_MAX = 512 * 1024;

    /**
     * Formats admis. Le SVG en est écarté à dessein : c'est un document XML, qui peut porter
     * du script exécuté par le navigateur si l'image est servie sur le domaine du site.
     */
    public static final Set<String> TYPES_ADMIS = Set.of("image/png", "image/jpeg", "image/webp");

    private final EtablissementRepository etablissementRepository;
    private final EtablissementCourantService etablissementCourantService;
    private final PlanService planService;

    /** Contrôle l'image sans l'enregistrer ; renvoie le type normalisé. */
    public String verifier(byte[] octets, String typeAnnonce) {
        if (octets == null || octets.length == 0) {
            throw new MetierException("FICHIER_VIDE", "Choisissez une image avant d'envoyer.");
        }
        if (octets.length > TAILLE_MAX) {
            throw new MetierException("TROP_LOURD", "Image trop lourde : " + (octets.length / 1024)
                    + " Ko pour un maximum de " + (TAILLE_MAX / 1024) + " Ko. Réduisez-la avant de réessayer.");
        }
        String type = typeAnnonce == null ? "" : typeAnnonce.toLowerCase(Locale.ROOT);
        if (!TYPES_ADMIS.contains(type)) {
            throw new MetierException("FORMAT_NON_PRIS", "Format non accepté. Envoyez une image PNG, JPEG ou WebP.");
        }
        // Le type annoncé vient du client : on vérifie la signature du fichier lui-même.
        if (!signatureConforme(octets, type)) {
            throw new MetierException("IMAGE_INVALIDE", "Ce fichier n'est pas une image PNG, JPEG ou WebP valide.");
        }
        return type;
    }

    @Transactional
    public void deposer(byte[] octets, String typeAnnonce) {
        Etablissement etablissement = courantAvecMarque();
        String type = verifier(octets, typeAnnonce);
        etablissement.setLogoDonnees(octets);
        etablissement.setLogoType(type);
        etablissementRepository.save(etablissement);
    }

    @Transactional
    public void retirer() {
        Etablissement etablissement = courantAvecMarque();
        etablissement.setLogoDonnees(null);
        etablissement.setLogoType(null);
        etablissementRepository.save(etablissement);
    }

    @Transactional(readOnly = true)
    public boolean aUnLogo() {
        Etablissement e = etablissementCourantService.courant();
        return e != null && e.getLogoDonnees() != null && e.getLogoDonnees().length > 0;
    }

    /** Lève une exception rendue en 403 si le plan ne comprend pas la marque. */
    public Etablissement courantAvecMarque() {
        Etablissement etablissement = etablissementCourantService.courant();
        if (etablissement == null) {
            throw new MetierException("INDISPONIBLE", "Aucun établissement rattaché à votre compte.");
        }
        planService.exiger(etablissement, Feature.BRANDING);
        return etablissement;
    }

    /** Premiers octets attendus pour chaque format admis. */
    public static boolean signatureConforme(byte[] o, String type) {
        if (o == null) return false;
        return switch (type) {
            case "image/png" -> o.length > 8 && (o[0] & 0xFF) == 0x89 && o[1] == 'P' && o[2] == 'N' && o[3] == 'G';
            case "image/jpeg", "image/jpg" -> o.length > 3 && (o[0] & 0xFF) == 0xFF && (o[1] & 0xFF) == 0xD8 && (o[2] & 0xFF) == 0xFF;
            case "image/webp" -> o.length > 12 && o[0] == 'R' && o[1] == 'I' && o[2] == 'F' && o[3] == 'F'
                    && o[8] == 'W' && o[9] == 'E' && o[10] == 'B' && o[11] == 'P';
            default -> false;
        };
    }
}
