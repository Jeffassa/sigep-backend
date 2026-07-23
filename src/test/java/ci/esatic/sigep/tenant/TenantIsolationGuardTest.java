package ci.esatic.sigep.tenant;

import ci.esatic.sigep.entity.Matiere;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GARANTIE AUTOMATIQUE d'isolation multi-tenant (défense contre l'oubli du développeur).
 *
 * <p>Deux invariants dont la violation ouvrirait une fuite inter-tenant silencieuse :
 * <ol>
 *   <li>Toute entité MÉTIER (hors entités globales listées) doit implémenter {@code TenantScoped}
 *       ET porter {@code @EntityListeners(TenantListener)} (garde @PostLoad anti-findById)
 *       ET {@code @Filter(name="tenantFilter")} (isolation en lecture).</li>
 *   <li>Toute requête NATIVE (à laquelle le filtre Hibernate ne s'applique pas) doit contenir
 *       le prédicat tenant {@code etablissement_id}.</li>
 * </ol>
 */
class TenantIsolationGuardTest {

    /** Entités volontairement NON cloisonnées (globales à la plateforme). */
    private static final Set<String> ENTITES_GLOBALES =
            Set.of("User", "Role", "RefreshToken", "Etablissement", "Paiement");

    @Test
    void toutesLesEntitesMetierSontCloisonneesParTenant() throws Exception {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<String> manquants = new ArrayList<>();
        for (var bd : provider.findCandidateComponents("ci.esatic.sigep.entity")) {
            Class<?> clazz = Class.forName(bd.getBeanClassName());
            if (ENTITES_GLOBALES.contains(clazz.getSimpleName())) continue;

            boolean scoped = TenantScoped.class.isAssignableFrom(clazz);
            boolean listener = clazz.isAnnotationPresent(EntityListeners.class)
                    && Arrays.asList(clazz.getAnnotation(EntityListeners.class).value()).contains(TenantListener.class);
            boolean filtre = clazz.isAnnotationPresent(Filter.class)
                    && "tenantFilter".equals(clazz.getAnnotation(Filter.class).name());

            if (!(scoped && listener && filtre)) {
                manquants.add(clazz.getSimpleName()
                        + " [TenantScoped=" + scoped + ", @EntityListeners=" + listener + ", @Filter=" + filtre + "]");
            }
        }

        assertThat(manquants)
                .as("Entités métier sans isolation tenant complète — ajouter TenantScoped + "
                        + "@EntityListeners(TenantListener) + @Filter(tenantFilter), ou déclarer l'entité globale")
                .isEmpty();
    }

    @Test
    void toutesLesRequetesNativesFiltrentParTenant() throws Exception {
        var provider = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isInterface();
            }
        };
        provider.addIncludeFilter((metadataReader, factory) -> true);

        List<String> fautives = new ArrayList<>();
        for (var bd : provider.findCandidateComponents("ci.esatic.sigep.repository")) {
            Class<?> repo = Class.forName(bd.getBeanClassName());
            for (Method m : repo.getMethods()) {
                Query q = m.getAnnotation(Query.class);
                if (q == null || !q.nativeQuery()) continue;
                String sql = (q.value() + " " + q.countQuery()).toLowerCase();
                if (!sql.contains("etablissement_id")) {
                    fautives.add(repo.getSimpleName() + "." + m.getName());
                }
            }
        }

        assertThat(fautives)
                .as("Requêtes natives sans prédicat tenant (etablissement_id) — le filtre Hibernate "
                        + "ne s'y applique pas : risque de fuite inter-tenant. Ajouter le prédicat.")
                .isEmpty();
    }

    // Garde-fou de compilation : référence une entité tenant pour ancrer le package.
    @SuppressWarnings("unused")
    private static final Class<?> ANCRE = Matiere.class;
}
