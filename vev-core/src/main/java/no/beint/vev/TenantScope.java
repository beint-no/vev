package no.beint.vev;

/**
 * An opaque, immutable, strongly typed tenant boundary carried by every transaction.
 *
 * @param <M> closed-model marker type
 * @param <T> tenant-key type
 */
public final class TenantScope<M, T> {
    private final Class<T> javaType;
    private final T tenantId;
    private final TenantAuthority.Claim<M> claim;

    TenantScope(Class<T> javaType, T tenantId, TenantAuthority.Claim<M> claim) {
        this.javaType = java.util.Objects.requireNonNull(javaType, "javaType");
        this.tenantId = java.util.Objects.requireNonNull(tenantId, "tenantId");
        this.claim = java.util.Objects.requireNonNull(claim, "claim");
        if (javaType.isPrimitive()) {
            throw new IllegalArgumentException("Tenant key types must not be primitive");
        }
        if (!javaType.isInstance(tenantId)) {
            throw new IllegalArgumentException("Tenant key must be a " + javaType.getName());
        }
        if (tenantId instanceof String text) {
            Names.requireStable(text, "tenantId", 128);
        }
    }

    /**
     * Returns the type of this scope's tenant key.
     *
     * @return exact generated tenant-key type
     */
    public Class<T> javaType() {
        return javaType;
    }

    /**
     * Returns the tenant key carried by this scope.
     *
     * @return validated tenant key
     */
    public T tenantId() {
        return tenantId;
    }

    boolean wasMintedWith(TenantAuthority.Claim<M> expectedClaim) {
        return claim == expectedClaim;
    }
}
