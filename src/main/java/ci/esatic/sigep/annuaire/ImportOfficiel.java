package ci.esatic.sigep.annuaire;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Dernier import d'une liste officielle : quand, combien d'établissements, et l'erreur éventuelle. */
@Entity
@Table(name = "officiels_imports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportOfficiel {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SourceOfficielle source;

    @Column(name = "date_import")
    private LocalDateTime dateImport;

    private Integer nombre;

    @Column(length = 300)
    private String erreur;
}
