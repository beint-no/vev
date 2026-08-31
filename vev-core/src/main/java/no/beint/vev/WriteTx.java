package no.beint.vev;

/**
 * Lexically scoped write capability which must not escape its executor callback.
 *
 * @param <M> closed-model marker type
 * @param <T> tenant-key type
 */
public interface WriteTx<M, T> extends ReadTx<M, T> {
    /**
     * Returns the explicit operations guarded by this write transaction.
     *
     * @return explicit read and write operations bound to this transaction
     */
    @Override
    WriteEntities<M> entities();
}
