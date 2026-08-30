package no.beint.vev;

/**
 * Lexically scoped read capability which must not escape its executor callback.
 *
 * @param <M> closed-model marker type
 * @param <T> tenant-key type
 */
public interface ReadTx<M, T> {
    /**
     * Returns the transaction's tenant boundary.
     *
     * @return immutable tenant boundary validated for this closed model
     */
    TenantScope<M, T> tenant();

    /**
     * Returns the read operations guarded by this transaction.
     *
     * @return detached read operations bound to this transaction
     */
    ReadEntities<M> entities();
}
