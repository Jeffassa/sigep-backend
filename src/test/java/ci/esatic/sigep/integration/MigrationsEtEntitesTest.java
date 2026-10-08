package ci.esatic.sigep.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les migrations ne sont jamais jouées par la suite de tests — ce test comble ce trou.
 *
 * <h2>Le trou</h2>
 *
 * <p>Les tests tournent sur H2, où Flyway est désactivé et le schéma fabriqué par Hibernate à
 * partir des entités ({@code ddl-auto: create-drop}). Autrement dit : <b>les fichiers de
 * migration ne sont exécutés par aucun test</b>, et le schéma de test est par construction
 * d'accord avec les entités, quoi que contiennent les migrations.
 *
 * <p>La production, elle, tourne sur PostgreSQL avec {@code ddl-auto: validate} et le schéma
 * construit par Flyway. Une colonne oubliée, mal nommée ou renommée dans une migration ne se
 * découvrirait donc qu'au <b>démarrage en production</b> — par un refus de démarrer, après le
 * déploiement, sur une application qui tournait très bien une minute plus tôt.
 *
 * <h2>Ce que ce test vérifie, et ce qu'il ne vérifie pas</h2>
 *
 * <p>Il compare des <b>noms</b> : chaque colonne qu'une entité déclare doit apparaître dans les
 * migrations, et chaque table aussi. C'est la divergence la plus fréquente, et la seule qu'on
 * puisse établir sans base PostgreSQL. Il ne dit rien des types, des longueurs ni des contraintes
 * — pour cela il faudrait démarrer un vrai PostgreSQL, ce que la chaîne d'intégration ne fait pas
 * encore. Mieux vaut un garde-fou partiel et honnête que pas de garde-fou du tout.
 */
class MigrationsEtEntitesTest {

    private static final Path ENTITES = Path.of("src/main/java/ci/esatic/sigep/entity");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /**
     * Entités vérifiées.
     *
     * <p>Liste explicite plutôt que balayage du paquet : plusieurs entités anciennes précèdent la
     * ligne de base des migrations ou passent par des tables de jointure, et les inclure
     * produirait un test bruyant que personne ne lirait. On y ajoute chaque nouvelle entité.
     */
    private static final List<Class<?>> VERIFIEES = List.of(
            ci.esatic.sigep.entity.ConfigurationSso.class,
            ci.esatic.sigep.entity.CleApi.class,
            ci.esatic.sigep.entity.Campus.class,
            ci.esatic.sigep.entity.EvenementSecurite.class);

    @Test
    void chaqueColonneDeclaree_existeDansLesMigrations() throws IOException {
        String sql = toutLeSql().toLowerCase(Locale.ROOT);
        List<String> manquantes = new ArrayList<>();

        for (Class<?> entite : VERIFIEES) {
            assertThat(entite.isAnnotationPresent(Entity.class))
                    .as(entite.getSimpleName() + " n'est pas une entite").isTrue();

            String table = table(entite);
            if (!sql.contains(table)) {
                manquantes.add(entite.getSimpleName() + " : table « " + table + " » absente");
                continue;   // inutile d'énumérer les colonnes d'une table introuvable
            }
            for (String colonne : colonnes(entite)) {
                if (!sql.contains(colonne)) {
                    manquantes.add(entite.getSimpleName() + " : colonne « " + colonne + " »");
                }
            }
        }

        assertThat(manquantes)
                .as("ces noms sont mappés par une entité mais n'apparaissent dans aucune "
                        + "migration : la production refuserait de démarrer (ddl-auto=validate), "
                        + "alors que les tests, eux, passent — ils fabriquent leur schéma depuis "
                        + "ces mêmes entités")
                .isEmpty();
    }

    private static String table(Class<?> entite) {
        Table t = entite.getAnnotation(Table.class);
        return (t != null && !t.name().isBlank() ? t.name() : entite.getSimpleName())
                .toLowerCase(Locale.ROOT);
    }

    /** Colonnes réellement persistées, telles que Hibernate les nommera. */
    private static List<String> colonnes(Class<?> entite) {
        List<String> noms = new ArrayList<>();
        for (Field f : entite.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
            if (f.isAnnotationPresent(Transient.class)) continue;
            Column c = f.getAnnotation(Column.class);
            noms.add(c != null && !c.name().isBlank()
                    ? c.name().toLowerCase(Locale.ROOT)
                    // Stratégie de nommage par défaut de Spring Boot : camelCase → snake_case.
                    : f.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT));
        }
        return noms;
    }

    private static String toutLeSql() throws IOException {
        StringBuilder tout = new StringBuilder();
        try (Stream<Path> fichiers = Files.list(MIGRATIONS)) {
            for (Path f : fichiers.filter(p -> p.toString().endsWith(".sql")).toList()) {
                tout.append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return tout.toString();
    }

    @Test
    void lesNumerosDeVersion_nesontPasDupliques() throws IOException {
        // Deux fichiers portant la même version font échouer Flyway au démarrage, et seulement
        // là : rien dans la compilation ni dans les tests ne s'en aperçoit.
        var vues = new java.util.HashMap<String, String>();
        List<String> doublons = new ArrayList<>();
        try (Stream<Path> fichiers = Files.list(MIGRATIONS)) {
            for (Path f : fichiers.filter(p -> p.toString().endsWith(".sql")).toList()) {
                String nom = f.getFileName().toString();
                String version = nom.substring(0, nom.indexOf("__"));
                String precedent = vues.put(version, nom);
                if (precedent != null) {
                    doublons.add(version + " : " + precedent + " et " + nom);
                }
            }
        }
        assertThat(doublons).as("Flyway refuse de demarrer sur une version dupliquee").isEmpty();
    }

    @Test
    void lesEntitesDuPaquet_sontToutesConnuesOuDeliberementEcartees() throws IOException {
        // Garde-fou du garde-fou : sans lui, une entité neuve échapperait silencieusement à la
        // vérification ci-dessus, et le trou se rouvrirait sans que personne le sache.
        List<String> inconnues = new ArrayList<>();
        var verifiees = VERIFIEES.stream().map(Class::getSimpleName).collect(
                java.util.stream.Collectors.toSet());

        try (Stream<Path> fichiers = Files.list(ENTITES)) {
            for (Path f : fichiers.filter(p -> p.toString().endsWith(".java")).toList()) {
                String nom = f.getFileName().toString().replace(".java", "");
                String source = Files.readString(f, StandardCharsets.UTF_8);
                if (!source.contains("@Entity")) continue;
                if (verifiees.contains(nom) || ANTERIEURES.contains(nom)) continue;
                inconnues.add(nom);
            }
        }

        assertThat(inconnues)
                .as("ajoutez ces entites a VERIFIEES (ou a ANTERIEURES si elles precedent la "
                        + "ligne de base des migrations)")
                .isEmpty();
    }

    /** Entités antérieures à la ligne de base, ou dont la table naît d'une jointure. */
    private static final java.util.Set<String> ANTERIEURES = java.util.Set.of(
            "User", "Role", "Enseignant", "Seance", "Emargement", "Matiere", "Classe", "Salle",
            "RapportPdf", "DemandeRattrapage", "RefreshToken", "Etablissement", "Paiement",
            "PaiementIntent");
}
