package no.beint.vev;

/**
 * Provider-independent entry point for lexical tenant-scoped transactions.
 *
 * <p>Normal failures are unchecked so database implementation details and checked exceptions do
 * not leak into application signatures.</p>
 *
 * @param <M> closed-model marker type
 * @param <T> tenant-key type
 */
public interface TransactionExecutor<M, T> {
    /**
     * Executes and materializes read work after validating the authority-bound tenant capability.
     *
     * @param tenant tenant capability claimed by this runtime
     * @param work lexical read callback
     * @param <R> materialized callback result type
     * @return materialized callback result
     */
    <R> R read(TenantScope<M, T> tenant, ReadWork<M, T, R> work);

    /**
     * Executes authority-bound explicit write work atomically or rolls it back.
     *
     * @param tenant tenant capability claimed by this runtime
     * @param work lexical write callback
     * @param <R> materialized callback result type
     * @return materialized callback result
     */
    <R> R write(TenantScope<M, T> tenant, WriteWork<M, T, R> work);
}
