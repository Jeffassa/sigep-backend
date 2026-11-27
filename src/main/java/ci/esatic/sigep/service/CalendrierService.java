package ci.esatic.sigep.service;

import ci.esatic.sigep.entity.Enseignant;
import ci.esatic.sigep.entity.PeriodeCalendrier;
import ci.esatic.sigep.entity.Seance;
import ci.esatic.sigep.entity.StatutEnseignant;
import ci.esatic.sigep.entity.StatutSeance;
import ci.esatic.sigep.entity.TypePeriode;
import ci.esatic.sigep.repository.EnseignantRepository;
import ci.esatic.sigep.repository.PeriodeCalendrierRepository;
import ci.esatic.sigep.repository.SeanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Vie de l'établissement : fermetures, semestres et remplacements d'enseignants. */
@Service
@RequiredArgsConstructor
public class CalendrierService {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PeriodeCalendrierRepository periodeRepository;
    private final SeanceRepository seanceRepository;
    private final EnseignantRepository enseignantRepository;
    private final MailService mailService;
    private final EtablissementCourantService etablissementCourantService;
    private final ci.esatic.sigep.repository.EtablissementRepository etablissementRepository;

    public record Remplacement(int remplacees, int conflits) {}

    /**
     * @param retirerSeances pour une fermeture : retire les séances non émargées de la période,
     *                       qui sinon resteraient comptées comme des oublis
     * @return nombre de séances retirées
     */
    @Transactional
    public int ajouterPeriode(TypePeriode type, String libelle, LocalDate debut, LocalDate fin, boolean retirerSeances) {
        LocalDate derniere = fin == null ? debut : fin;
        String nom = libelle == null ? "" : libelle.trim();
        if (type == null || nom.isEmpty() || debut == null) {
            throw new IllegalArgumentException("Indiquez le type, le nom et la date de début.");
        }
        if (derniere.isBefore(debut)) {
            throw new IllegalArgumentException("La date de fin précède la date de début.");
        }
        periodeRepository.save(PeriodeCalendrier.builder()
                .type(type).libelle(nom).debut(debut).fin(derniere).build());
        if (!type.ferme() || !retirerSeances) return 0;
        List<Seance> aRetirer = seanceRepository.seancesRetirables(debut, derniere);
        seanceRepository.deleteAll(aRetirer);
        return aRetirer.size();
    }

    public List<PeriodeCalendrier> fermetures() {
        return periodeRepository.findAllByOrderByDebutAsc().stream().filter(p -> p.getType().ferme()).toList();
    }

    public List<PeriodeCalendrier> semestres() {
        return periodeRepository.findByTypeOrderByDebutAsc(TypePeriode.SEMESTRE);
    }

    public Optional<PeriodeCalendrier> semestreDe(LocalDate jour) {
        return semestres().stream().filter(s -> s.contient(jour)).findFirst();
    }

    /** Tolérance d'émargement, au plus deux heures avant comme après. */
    public static final int TOLERANCE_MAX_MINUTES = 120;

    @Transactional
    public void reglerTolerance(int avant, int apres) {
        var etab = etablissementCourantService.courant();
        if (etab == null || avant < 0 || apres < 0 || avant > TOLERANCE_MAX_MINUTES || apres > TOLERANCE_MAX_MINUTES) {
            throw new IllegalArgumentException("La tolérance doit rester entre 0 et " + TOLERANCE_MAX_MINUTES + " minutes.");
        }
        etab.setToleranceAvantMinutes(avant);
        etab.setToleranceApresMinutes(apres);
        etablissementRepository.save(etab);
    }

    /** Retire une période du calendrier. Les séances déjà retirées ne reviennent pas. */
    @Transactional
    public PeriodeCalendrier supprimerPeriode(Long id) {
        PeriodeCalendrier p = periode(id);
        periodeRepository.delete(p);
        return p;
    }

    /** La période, si elle appartient à l'établissement. */
    @Transactional(readOnly = true)
    public PeriodeCalendrier periode(Long id) {
        return periodeRepository.findById(id)
                .orElseThrow(() -> new ci.esatic.sigep.exception.ResourceNotFoundException("Periode", "id", id));
    }

    /**
     * Ce que ferait un remplacement, sans rien changer ni écrire à personne.
     */
    @Transactional(readOnly = true)
    public Remplacement simulerRemplacement(Long absentId, Long remplacantId, LocalDate debut, LocalDate fin) {
        Repartition r = repartir(absentId, remplacantId, debut, fin);
        return new Remplacement(r.aConfier().size(), r.conflits());
    }

    /**
     * Confie à un remplaçant les séances non encore assurées d'un enseignant absent. Un créneau où
     * le remplaçant enseigne déjà reste à l'enseignant prévu et est compté comme conflit.
     */
    @Transactional
    public Remplacement remplacer(Long absentId, Long remplacantId, LocalDate debut, LocalDate fin) {
        Repartition r = repartir(absentId, remplacantId, debut, fin);
        List<String> lignes = new ArrayList<>();
        for (Seance s : r.aConfier()) {
            // Après un second remplacement, c'est toujours l'enseignant prévu au départ qu'on garde.
            if (s.getEnseignantRemplace() == null) s.setEnseignantRemplace(r.absent());
            s.setEnseignant(r.remplacant());
            lignes.add(s.getDate().format(JOUR) + " " + s.getHeureDebut() + "-" + s.getHeureFin()
                    + " : " + s.getMatiere().getLibelle() + " (" + s.getClasse().getLibelle() + ")");
        }
        Enseignant remplacant = r.remplacant();
        Enseignant absent = r.absent();
        if (!lignes.isEmpty() && remplacant.getUser() != null) {
            mailService.envoyerMessage(remplacant.getUser().getEmail(), "SIGEP - Séances à remplacer",
                    "Bonjour " + remplacant.getPrenom() + ",\n\nVous remplacez " + absent.getPrenom() + " "
                            + absent.getNom() + " sur ces séances :\n\n" + String.join("\n", lignes)
                            + "\n\nElles apparaissent dans votre planning.\n\nSIGEP");
        }
        return new Remplacement(lignes.size(), r.conflits());
    }

    private record Repartition(Enseignant absent, Enseignant remplacant, List<Seance> aConfier, int conflits) {}

    /** Calcule, sans rien modifier, quelles séances iraient au remplaçant. */
    private Repartition repartir(Long absentId, Long remplacantId, LocalDate debut, LocalDate fin) {
        if (Objects.equals(absentId, remplacantId)) {
            throw new IllegalArgumentException("Choisissez deux enseignants différents.");
        }
        if (debut == null || fin == null || fin.isBefore(debut)) {
            throw new IllegalArgumentException("Indiquez une période valide.");
        }
        Enseignant absent = enseignantRepository.findById(absentId)
                .orElseThrow(() -> new IllegalArgumentException("Enseignant absent introuvable."));
        Enseignant remplacant = enseignantRepository.findById(remplacantId)
                .orElseThrow(() -> new IllegalArgumentException("Remplaçant introuvable."));
        if (remplacant.getStatut() != StatutEnseignant.VALIDATED) {
            throw new IllegalArgumentException("Le remplaçant doit avoir un compte validé.");
        }

        List<Seance> occupees = new ArrayList<>(seanceRepository.findByEnseignantIdAndDateBetween(remplacantId, debut, fin));
        List<Seance> aConfier = new ArrayList<>();
        int conflits = 0;
        for (Seance s : seanceRepository.findByEnseignantIdAndDateBetween(absentId, debut, fin)) {
            if (s.getStatut() != StatutSeance.A_FAIRE) continue;
            if (occupees.stream().anyMatch(o -> chevauche(o, s))) {
                conflits++;
                continue;
            }
            occupees.add(s);
            aConfier.add(s);
        }
        return new Repartition(absent, remplacant, aConfier, conflits);
    }

    private static boolean chevauche(Seance a, Seance b) {
        return a.getDate().equals(b.getDate())
                && a.getHeureDebut().isBefore(b.getHeureFin())
                && b.getHeureDebut().isBefore(a.getHeureFin());
    }
}
