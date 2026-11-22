package ci.esatic.sigep.annuaire;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/** Suggestions d'établissements pour le formulaire d'inscription (public, lecture seule). */
@RestController
public class AnnuaireController {

    private final AnnuaireService annuaire;

    public AnnuaireController(AnnuaireService annuaire) {
        this.annuaire = annuaire;
    }

    @GetMapping("/api/saas/annuaire")
    public ResponseEntity<List<AnnuaireService.Suggestion>> rechercher(
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "pays", required = false) String pays) {
        String recherche = q.length() > 80 ? q.substring(0, 80) : q;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(annuaire.rechercher(recherche, pays));
    }
}
