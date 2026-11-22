package ci.esatic.sigep.annuaire;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportOfficielRepository extends JpaRepository<ImportOfficiel, SourceOfficielle> {
}
