package ci.esatic.sigep.tenant;

/**
 * Contexte tenant courant (par thread). Renseigné à chaque requête à partir de
 * l'utilisateur authentifié, puis lu pour : (1) activer le filtre d'isolation
 * Hibernate, (2) estampiller les nouvelles entités (cf. TenantListener).
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long etablissementId) { CURRENT.set(etablissementId); }

    public static Long get() { return CURRENT.get(); }

    public static void clear() { CURRENT.remove(); }
}
