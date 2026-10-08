package ci.esatic.sigep.repository;

import ci.esatic.sigep.entity.ConfigurationSso;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Accès aux configurations SSO.
 *
 * <p>Une seule méthode de lecture, et elle nomme l'établissement. C'est délibéré : la connexion
 * SSO se produit avant toute authentification, donc sans contexte tenant et sans filtre Hibernate.
 * Une méthode comme {@code findByIssuer} aurait permis, un jour, de retrouver une configuration
 * sans savoir à qui elle appartient — exactement le genre de raccourci qui finit par faire entrer
 * quelqu'un dans le mauvais établissement.
 */
public interface ConfigurationSsoRepository extends JpaRepository<ConfigurationSso, Long> {

    Optional<ConfigurationSso> findByEtablissementId(Long etablissementId);

    /**
     * Toutes les configurations actives — la seule lecture qui traverse les établissements.
     *
     * <p>Elle sert uniquement à retrouver un établissement à partir du domaine d'une adresse,
     * avant toute authentification : à cet instant, par construction, on ne sait pas encore chez
     * qui l'on est. Rien de ce qu'elle rend n'est montré au visiteur — seul l'identifiant trouvé
     * sert à le rediriger — et le secret client qu'elle rapporte reste chiffré.
     */
    java.util.List<ConfigurationSso> findByActifTrue();
}
