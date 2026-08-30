package no.beint.vev;

/**
 * Application work executed within one lexical write transaction.
 *
 * @param <M> closed-model marker type
 * @param <T> tenant-key type
 * @param <R> callback result type
 */
@FunctionalInterface
public interface WriteWork<M, T, R> {
    /**
     * Runs application work inside a guarded write transaction.
     *
     * @param transaction non-escaping write capability
     * @return callback result
     */
    R run(WriteTx<M, T> transaction);
}
