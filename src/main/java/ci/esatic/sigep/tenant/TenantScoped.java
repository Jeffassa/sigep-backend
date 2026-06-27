package ci.esatic.sigep.tenant;

/**
 * Marque une entité rattachée à un établissement (tenant). Permet l'estampillage
 * automatique à la création (TenantListener) et l'isolation par filtre Hibernate.
 */
public interface TenantScoped {
    Long getEtablissementId();
    void setEtablissementId(Long etablissementId);
}
