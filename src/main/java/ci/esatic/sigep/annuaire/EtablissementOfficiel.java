package ci.esatic.sigep.annuaire;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Un établissement autorisé par l'État, recopié d'une liste officielle. Sert à proposer le nom à
 * l'inscription et à reconnaître l'établissement qui s'inscrit : aucun client n'y figure.
 */
@Entity
@Table(name = "etablissements_officiels",
        indexes = {@Index(name = "idx_officiels_source", columnList = "source"),
                   @Index(name = "idx_officiels_recherche", columnList = "nom_recherche"),
                   @Index(name = "idx_officiels_cle", columnList = "nom_cle")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EtablissementOfficiel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SourceOfficielle source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CategorieOfficielle categorie;

    /** Code de l'établissement au ministère de l'Éducation nationale (six chiffres), s'il est publié. */
    @Column(name = "code_officiel", length = 20)
    private String codeOfficiel;

    @Column(nullable = false, length = 255)
    private String nom;

    /** Le nom en minuscules, sans accents ni ponctuation : la clé de recherche. */
    @Column(name = "nom_recherche", nullable = false, length = 255)
    private String nomRecherche;

    /** Comme la clé de recherche, sans le sigle entre parenthèses : la clé de rapprochement. */
    @Column(name = "nom_cle", nullable = false, length = 255)
    private String nomCle;

    @Column(length = 120)
    private String ville;

    @Column(length = 120)
    private String commune;

    @Column(name = "date_import", nullable = false)
    private LocalDateTime dateImport;

    public static EtablissementOfficiel de(SourceOfficielle source, CategorieOfficielle categorie, String code,
                                           String nom, String ville, String commune, LocalDateTime date) {
        String propre = nom.strip().replaceAll("\\s+", " ");
        return EtablissementOfficiel.builder().source(source).categorie(categorie).codeOfficiel(code)
                .nom(tronquer(propre, 255)).nomRecherche(tronquer(TexteRecherche.normaliser(propre), 255))
                .nomCle(tronquer(TexteRecherche.cle(propre), 255))
                .ville(tronquer(ville, 120)).commune(tronquer(commune, 120)).dateImport(date).build();
    }

    private static String tronquer(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
