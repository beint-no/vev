package no.beint.vev.benchmark.hibernate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BatchUpdateWorkloadTest {
    @Test
    void exactProviderTransitionIsConsumed() {
        assertDoesNotThrow(() -> BatchUpdateWorkload.checksum(4L, BatchUpdateWorkload.state(5L)));
    }

    @Test
    void wrongPayloadTransitionIsRejected() {
        var returned = new ArrayList<>(BatchUpdateWorkload.state(1L));
        var first = returned.getFirst();
        returned.set(0, new BenchmarkUpdateAccount(
                first.id(),
                first.tenantId(),
                first.version(),
                BatchUpdateWorkload.balance(first.id(), 2L)));

        assertThrows(IllegalStateException.class, () -> BatchUpdateWorkload.checksum(0L, returned));
    }
}
