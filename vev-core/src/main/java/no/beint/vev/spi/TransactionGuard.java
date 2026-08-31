package no.beint.vev.spi;

/**
 * Provider-held guard for a single lexical transaction capability.
 *
 * <p>Providers call {@link #checkUsable()} before every operation and {@link #poison(Throwable)}
 * after an operation reaches an uncertain or failed database state.</p>
 */
public sealed interface TransactionGuard permits LexicalTransactionGuard {
    /** Fails after scope exit, on a foreign thread, in a shadowing nested scope, or after poison. */
    void checkUsable();

    /**
     * Marks the transaction rollback-only while preserving the first failure as the cause.
     *
     * @param failure provider failure that made the transaction unsafe to complete
     */
    void poison(Throwable failure);

    /**
     * Reports whether a provider failure has made successful completion impossible.
     *
     * @return whether this guard has been poisoned
     */
    boolean isPoisoned();
}
