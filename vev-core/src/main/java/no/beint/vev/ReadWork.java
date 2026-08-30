package no.beint.vev;

/**
 * Application work executed within one lexical read transaction.
 *
 * @param <M> closed-model marker type
 * @param <T> tenant-key type
 * @param <R> callback result type
 */
@FunctionalInterface
public interface ReadWork<M, T, R> {
    /**
     * Runs application work inside a guarded read transaction.
     *
     * @param transaction non-escaping read capability
     * @return callback result
     */
    R run(ReadTx<M, T> transaction);
}
