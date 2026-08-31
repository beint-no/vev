package no.beint.vev.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class TransactionScopesTest {
    @Test
    void capabilityExpiresWhenItsCallbackReturns() {
        AtomicReference<TransactionGuard> escaped = new AtomicReference<>();

        int result = TransactionScopes.call(guard -> {
            guard.checkUsable();
            assertFalse(guard.isPoisoned());
            escaped.set(guard);
            return 42;
        });

        assertEquals(42, result);
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> escaped.get().checkUsable());
        assertTrue(failure.getMessage().contains("no longer"));
    }

    @Test
    void capabilityCannotCrossAThreadBoundary() {
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();

        TransactionScopes.call(guard -> {
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    guard.checkUsable();
                } catch (Throwable failure) {
                    observedFailure.set(failure);
                }
            });
            join(thread);
            guard.checkUsable();
            return null;
        });

        Throwable failure = observedFailure.get();
        assertTrue(failure instanceof IllegalStateException);
        assertTrue(failure.getMessage().contains("different thread"));
    }

    @Test
    void poisonedCapabilityCannotCompleteSuccessfully() {
        IllegalArgumentException databaseFailure = new IllegalArgumentException("constraint violation");
        IllegalArgumentException rollbackFailure = new IllegalArgumentException("rollback failed");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                TransactionScopes.call(guard -> {
                    guard.poison(databaseFailure);
                    guard.poison(rollbackFailure);
                    assertTrue(guard.isPoisoned());
                    return "ignored";
                }));

        assertSame(databaseFailure, failure.getCause());
        assertSame(rollbackFailure, databaseFailure.getSuppressed()[0]);
    }

    @Test
    void nestedScopesAreRejectedWithoutPoisoningTheOuterCapability() {
        TransactionScopes.call(outer -> {
            outer.checkUsable();
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> TransactionScopes.call(inner -> null));
            assertTrue(failure.getMessage().contains("Nested"));
            outer.checkUsable();
            return null;
        });
    }

    @Test
    void callbackFailureIsRethrownAndCapabilityIsClosed() {
        AtomicReference<TransactionGuard> escaped = new AtomicReference<>();
        IllegalArgumentException callbackFailure = new IllegalArgumentException("failed");

        IllegalArgumentException observed = assertThrows(IllegalArgumentException.class, () ->
                TransactionScopes.call(guard -> {
                    escaped.set(guard);
                    throw callbackFailure;
                }));

        assertSame(callbackFailure, observed);
        assertThrows(IllegalStateException.class, () -> escaped.get().checkUsable());
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while joining virtual thread", interrupted);
        }
    }
}
