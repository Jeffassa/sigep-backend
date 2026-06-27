package ci.esatic.sigep.tenant;

import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;

/**
 * Garde-fou tenant sur les entités :
 *  - {@code @PrePersist} : estampille automatiquement une nouvelle entité avec l'établissement
 *    courant (TenantContext) -> impossible de créer « chez un autre établissement ».
 *  - {@code @PostLoad} : refuse une entité chargée appartenant à un AUTRE tenant que le courant.
 *    Indispensable car le filtre Hibernate ne s'applique PAS au chargement par identifiant
 *    ({@code findById}) -> sans cela, un GET .../{id} fuiterait entre établissements.
 */
public class TenantListener {

    @PrePersist
    public void avantInsertion(Object entity) {
        if (entity instanceof TenantScoped scoped && scoped.getEtablissementId() == null) {
            Long tenant = TenantContext.get();
            if (tenant != null) {
                scoped.setEtablissementId(tenant);
            }
        }
    }

    @PostLoad
    public void apresChargement(Object entity) {
        if (entity instanceof TenantScoped scoped) {
            Long tenant = TenantContext.get();
            if (tenant != null && scoped.getEtablissementId() != null
                    && !tenant.equals(scoped.getEtablissementId())) {
                throw new AccesTenantRefuseException();
            }
        }
    }
}
