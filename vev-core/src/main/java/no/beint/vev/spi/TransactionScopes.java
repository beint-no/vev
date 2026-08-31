package no.beint.vev.spi;

import java.util.Objects;
import java.util.function.Function;

/** Creates strongly guarded transaction callback scopes for database providers. */
public final class TransactionScopes {
    private TransactionScopes() {
    }

    /**
     * Runs provider work with a guard bound by {@link ScopedValue} to the current thread and
     * lexical callback. A poisoned guard cannot return successfully.
     *
     * @param work provider transaction lifecycle
     * @param <R> result type
     * @return provider result
     */
    public static <R> R call(Function<? super TransactionGuard, ? extends R> work) {
        Objects.requireNonNull(work, "work");
        if (LexicalTransactionGuard.CURRENT.isBound()) {
            throw new IllegalStateException("Nested Vev transactions require an explicit future propagation contract");
        }
        LexicalTransactionGuard guard = new LexicalTransactionGuard(Thread.currentThread());
        try {
            return ScopedValue.where(LexicalTransactionGuard.CURRENT, guard).call(() -> {
                R result = work.apply(guard);
                guard.checkUsable();
                return result;
            });
        } catch (RuntimeException | Error failure) {
            guard.poisonFromScope(failure);
            throw failure;
        } finally {
            guard.closeFromScope();
        }
    }
}
