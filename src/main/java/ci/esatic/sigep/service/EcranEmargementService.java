package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Etablissement;
import ci.esatic.sigep.exception.MetierException;
import ci.esatic.sigep.repository.EtablissementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Réglages de l'écran d'émargement : zone de présence et réseau autorisé.
 *
 * <p>Règles partagées par la console et l'API d'agent. La clé de l'écran n'est pas ici : sa
 * régénération reste un geste de la console.
 */
@Service
@RequiredArgsConstructor
public class EcranEmargementService {

    private final EtablissementCourantService etablissementCourantService;
    private final EtablissementRepository etablissementRepository;

    public record Etat(boolean pret, boolean zoneDefinie, Double latitude, Double longitude,
                       int rayon, String reseaux) { }

    @Transactional(readOnly = true)
    public Etat etat() {
        Etablissement e = courant();
        return new Etat(e.getSlug() != null, e.isZoneDefinie(), e.getZoneLatitude(), e.getZoneLongitude(),
                e.getZoneRayonMetres() != null ? e.getZoneRayonMetres() : ZoneEmargement.RAYON_DEFAUT,
                e.getEcranReseaux());
    }

    /** Un téléphone hors de ce cercle ne peut pas émarger. */
    @Transactional
    public void reglerZone(String coordonnees, Integer rayon) {
        Etablissement e = courant();
        double[] point;
        try {
            point = ZoneEmargement.lireCoordonnees(coordonnees == null ? "" : coordonnees.trim());
        } catch (IllegalArgumentException ex) {
            throw new MetierException("ZONE_INVALIDE",
                    "Coordonnées illisibles : indiquez « latitude, longitude » (ex. 5.3364, -4.0267).");
        }
        int r = rayon == null ? ZoneEmargement.RAYON_DEFAUT : rayon;
        if (r < ZoneEmargement.RAYON_MIN || r > ZoneEmargement.RAYON_MAX) {
            throw new MetierException("ZONE_INVALIDE", "Rayon hors limites : entre " + ZoneEmargement.RAYON_MIN
                    + " et " + ZoneEmargement.RAYON_MAX + " mètres.");
        }
        e.setZoneLatitude(point[0]);
        e.setZoneLongitude(point[1]);
        e.setZoneRayonMetres(r);
        etablissementRepository.save(e);
    }

    /** Retire le contrôle de position : on émarge alors de partout. */
    @Transactional
    public void retirerZone() {
        Etablissement e = courant();
        e.setZoneLatitude(null);
        e.setZoneLongitude(null);
        e.setZoneRayonMetres(null);
        etablissementRepository.save(e);
    }

    /**
     * Réseaux d'où l'écran peut s'afficher. Vide : partout où son cookie l'autorise.
     *
     * @return la liste telle qu'enregistrée, normalisée
     */
    @Transactional
    public String reglerReseau(String saisie) {
        Etablissement e = courant();
        String propre;
        try {
            List<String> entrees = ReseauEcran.lire(saisie == null ? "" : saisie.trim());
            propre = String.join(", ", entrees);
        } catch (IllegalArgumentException ex) {
            throw new MetierException("RESEAU_INVALIDE", ex.getMessage());
        }
        if (propre.length() > ReseauEcran.LONGUEUR_MAX) {
            throw new MetierException("RESEAU_INVALIDE", "liste trop longue");
        }
        e.setEcranReseaux(propre.isEmpty() ? null : propre);
        etablissementRepository.save(e);
        return propre;
    }

    private Etablissement courant() {
        Etablissement e = etablissementCourantService.courant();
        if (e == null) throw new MetierException("INDISPONIBLE", "Aucun établissement rattaché à ce compte.");
        return e;
    }
}
