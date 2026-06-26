package ci.esatic.sigep.tenant;

import jakarta.persistence.PrePersist;

/**
 * Estampille automatiquement toute nouvelle entité tenant-scoped avec l'établissement
 * courant (TenantContext) si elle n'en a pas déjà un. Empêche de créer une donnée « chez
 * un autre établissement » et évite d'oublier de poser le tenant à la main dans un service.
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
}
