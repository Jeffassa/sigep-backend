/**
 * Définition unique du filtre d'isolation multi-tenant. Les entités tenant-scoped
 * portent {@code @Filter(name = "tenantFilter", condition = "etablissement_id = :tenantId")} ;
 * il est activé par requête dans TenantInterceptor.
 */
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
package ci.esatic.sigep.entity;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
