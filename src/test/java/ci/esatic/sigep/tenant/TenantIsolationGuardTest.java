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

    /**
     * Entités volontairement NON cloisonnées (globales à la plateforme).
     *
     * <p>{@code Paiement} et {@code PaiementIntent} sont des écritures de niveau plateforme,
     * manipulées HORS contexte tenant : le super admin les consulte toutes, et le relanceur
     * de paiements Mobile Money s'exécute dans un cron sans TenantContext — les filtrer les
     * rendrait invisibles à ce cron, donc des abonnements payés ne seraient jamais crédités.
     * L'appartenance à l'établissement est vérifiée EXPLICITEMENT côté contrôleur
     * (cf. MobileMoneyWebController.intentAutorisee).
     */
    private static final Set<String> ENTITES_GLOBALES =
            Set.of("User", "Role", "RefreshToken", "Etablissement", "Paiement", "PaiementIntent",
                   // Journal de securite : global A DESSEIN. Une part des evenements survient
                   // avant toute identification d'etablissement, et ceux qui comptent le plus
                   // sont les tentatives de FRANCHIR le cloisonnement — les filtrer par tenant
                   // reviendrait a les cacher au super-administrateur.
                   "EvenementSecurite");

    /**
     * Requêtes natives autorisées à ne pas filtrer par établissement.
     *
     * <p>La liste doit rester courte et chaque entrée porter sa raison : une requête native
     * échappe au filtre Hibernate, et l'y soustraire sans justification ouvrirait une lecture
     * entre établissements. On inscrit donc l'exception ici, là où elle se relit, plutôt que
     * d'assouplir la règle.
     *
     * <p><b>CleApiRepository.chercherActiveParEmpreinte</b> : c'est la requête
     * d'authentification de l'API. Au moment où elle s'exécute, l'établissement n'est pas encore
     * connu — c'est précisément la clé qui va le désigner. Le filtrer par un établissement qu'on
     * ignore encore n'aurait aucun sens. L'établissement lu devient ensuite le seul périmètre de
     * la requête, posé par TenantInterceptor à partir de CleApiPrincipal.
     */
    private static final Set<String> REQUETES_SANS_TENANT_JUSTIFIEES =
            Set.of("CleApiRepository.chercherActiveParEmpreinte");

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
                String signature = repo.getSimpleName() + "." + m.getName();
                if (!sql.contains("etablissement_id")
                        && !REQUETES_SANS_TENANT_JUSTIFIEES.contains(signature)) {
                    fautives.add(signature);
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
