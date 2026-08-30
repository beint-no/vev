package no.beint.vev;

import java.util.Objects;

/**
 * The unique capability allowed to create tenant scopes for one verified model runtime.
 *
 * <p>An authority is bound to a generated model identity when it is created. A database runtime reserves it before
 * verification and claims it only after verification succeeds. An authority can be claimed once, and scopes can be
 * minted only after that claim. This prevents one scope capability from authorizing multiple runtime or database
 * boundaries.</p>
 *
 * @param <M> closed-model marker type
 * @param <T> tenant-key type
 */
public final class TenantAuthority<M, T> {
    private final Class<M> modelType;
    private final ModelIdentity modelIdentity;
    private final Class<T> tenantType;
    private Object runtimeState;

    private TenantAuthority(Class<M> modelType, ModelIdentity modelIdentity, Class<T> tenantType) {
        this.modelType = Objects.requireNonNull(modelType, "modelType");
        this.modelIdentity = Objects.requireNonNull(modelIdentity, "modelIdentity");
        this.tenantType = Objects.requireNonNull(tenantType, "tenantType");
        if (modelType.isPrimitive()) {
            throw new IllegalArgumentException("Model marker types must not be primitive");
        }
        if (tenantType.isPrimitive()) {
            throw new IllegalArgumentException("Tenant key types must not be primitive");
        }
    }

    /**
     * Creates an unclaimed authority for exactly one generated model runtime.
     *
     * @param modelType generated closed-model marker class
     * @param modelIdentity generated closed-model identity
     * @param tenantType exact generated tenant-key type
     * @param <M> closed-model marker type
     * @param <T> tenant-key type
     * @return a new unclaimed authority
     */
    public static <M, T> TenantAuthority<M, T> create(
            Class<M> modelType,
            ModelIdentity modelIdentity,
            Class<T> tenantType) {
        return new TenantAuthority<>(modelType, modelIdentity, tenantType);
    }

    /**
     * Returns the tenant-key type accepted by this authority.
     *
     * @return exact tenant-key type
     */
    public Class<T> tenantType() {
        return tenantType;
    }

    /**
     * Mints a validated tenant scope for the one runtime which claimed this authority.
     *
     * @param tenantId tenant key value
     * @return opaque tenant scope
     */
    public synchronized TenantScope<M, T> scope(T tenantId) {
        if (!(runtimeState instanceof Claim<?> rawClaim)) {
            throw new IllegalStateException("Tenant authority has not been claimed by a verified Vev runtime");
        }
        @SuppressWarnings("unchecked")
        Claim<M> claim = (Claim<M>) rawClaim;
        return new TenantScope<>(tenantType, tenantId, claim);
    }

    /**
     * Reserves this authority for database verification by one runtime.
     *
     * <p>This provider-facing operation does not claim the authority. Closing an unclaimed reservation releases it.
     * The runtime must call {@link Reservation#claim()} only after database verification succeeds.</p>
     *
     * @param runtimeModelIdentity model identity presented by the runtime
     * @return exclusive, releasable verification reservation
     */
    public synchronized Reservation<M> reserve(ModelIdentity runtimeModelIdentity) {
        Objects.requireNonNull(runtimeModelIdentity, "runtimeModelIdentity");
        if (!modelIdentity.equals(runtimeModelIdentity)) {
            throw new IllegalArgumentException("Tenant authority belongs to a different generated model identity");
        }
        if (runtimeState != null) {
            throw new IllegalStateException("Tenant authority is already reserved or claimed by another Vev runtime");
        }
        Object token = new Object();
        runtimeState = token;
        return new Reservation<>(this, token);
    }

    /**
     * Verifies a tenant scope against the exact successful runtime claim.
     *
     * @param scope tenant scope
     * @param expectedClaim runtime claim retained by the executor
     * @return verified tenant scope
     */
    public synchronized TenantScope<M, T> requireScope(
            TenantScope<M, T> scope,
            Claim<M> expectedClaim) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(expectedClaim, "expectedClaim");
        if (runtimeState != expectedClaim || !scope.wasMintedWith(expectedClaim)) {
            throw new IllegalArgumentException("Tenant scope was not minted for the required Vev runtime");
        }
        return scope;
    }

    private synchronized Claim<M> claim(Object token) {
        if (runtimeState != token) {
            throw new IllegalStateException("Tenant authority reservation is no longer active");
        }
        Claim<M> claim = new Claim<>();
        runtimeState = claim;
        return claim;
    }

    private synchronized void release(Object token) {
        if (runtimeState == token) {
            runtimeState = null;
        }
    }

    /**
     * Opaque successful-runtime claim required by every accepted tenant scope.
     *
     * @param <M> closed-model marker type
     */
    public static final class Claim<M> {
        private Claim() {
        }
    }

    /**
     * Exclusive, releasable reservation held while a runtime verifies its database boundary.
     *
     * @param <M> closed-model marker type
     */
    public static final class Reservation<M> implements AutoCloseable {
        private final TenantAuthority<M, ?> authority;
        private final Object token;
        private boolean completed;

        private Reservation(TenantAuthority<M, ?> authority, Object token) {
            this.authority = authority;
            this.token = token;
        }

        /**
         * Permanently claims the authority after successful database verification.
         *
         * @return opaque runtime claim
         */
        public synchronized Claim<M> claim() {
            if (completed) {
                throw new IllegalStateException("Tenant authority reservation is already complete");
            }
            Claim<M> claim = authority.claim(token);
            completed = true;
            return claim;
        }

        /** Releases this reservation unless it has already become the permanent runtime claim. */
        @Override
        public synchronized void close() {
            if (!completed) {
                authority.release(token);
                completed = true;
            }
        }
    }
}
