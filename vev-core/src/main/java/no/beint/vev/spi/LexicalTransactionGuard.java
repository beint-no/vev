package no.beint.vev.spi;

import java.util.Objects;

final class LexicalTransactionGuard implements TransactionGuard {
    static final ScopedValue<LexicalTransactionGuard> CURRENT = ScopedValue.newInstance();

    private enum State {
        OPEN,
        POISONED,
        CLOSED
    }

    private final Thread owner;
    private volatile State state = State.OPEN;
    private volatile Throwable poison;

    LexicalTransactionGuard(Thread owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override
    public void checkUsable() {
        checkOwner();
        State observedState = state;
        if (observedState == State.CLOSED) {
            throw new IllegalStateException("Transaction capability is no longer in its lexical scope");
        }
        if (!CURRENT.isBound() || CURRENT.get() != this) {
            throw new IllegalStateException("Transaction capability is outside its lexical scope");
        }
        if (observedState == State.POISONED) {
            throw new IllegalStateException("Transaction is poisoned and must roll back", poison);
        }
    }

    @Override
    public void poison(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        checkOwner();
        ensureInScope();
        if (state == State.OPEN) {
            poison = failure;
            state = State.POISONED;
        } else if (poison != failure) {
            poison.addSuppressed(failure);
        }
    }

    @Override
    public boolean isPoisoned() {
        checkOwner();
        if (state == State.CLOSED) {
            throw new IllegalStateException("Transaction capability is no longer in its lexical scope");
        }
        ensureInScope();
        return state == State.POISONED;
    }

    void poisonFromScope(Throwable failure) {
        if (state == State.OPEN) {
            poison = failure;
            state = State.POISONED;
        }
    }

    void closeFromScope() {
        state = State.CLOSED;
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Transaction capability belongs to a different thread");
        }
    }

    private void ensureInScope() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Transaction capability is no longer in its lexical scope");
        }
        if (!CURRENT.isBound() || CURRENT.get() != this) {
            throw new IllegalStateException("Transaction capability is outside its lexical scope");
        }
    }
}
