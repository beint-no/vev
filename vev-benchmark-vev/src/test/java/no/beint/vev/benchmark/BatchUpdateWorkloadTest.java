package no.beint.vev.benchmark;

import no.beint.vev.Batch;
import no.beint.vev.MutationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BatchUpdateWorkloadTest {
    @Test
    void providerAndRawOutcomesUseTheSameExactChecksum() {
        var requested = BatchUpdateWorkload.request(4L);
        var returned = BatchUpdateWorkload.state(5L);
        var appliedValues = new ArrayList<MutationResult.Applied<
                BenchmarkModelVev.Model, UpdateAccount, Long, Long>>(BatchUpdateWorkload.SIZE);
        for (int index = 0; index < BatchUpdateWorkload.SIZE; index++) {
            var expected = requested.get(index);
            var actual = returned.get(index);
            appliedValues.add(new MutationResult.Applied<>(
                    UpdateAccountVev.INSTANCE.key(actual.id()),
                    expected.version(),
                    actual.version(),
                    actual));
        }

        assertEquals(
                BatchUpdateWorkload.checksum(requested, returned),
                BatchUpdateWorkload.checksum(requested, Batch.copyOf(appliedValues)));
    }

    @Test
    void wrongVersionTransitionIsRejected() {
        var requested = BatchUpdateWorkload.request(0L);
        var returned = new ArrayList<>(BatchUpdateWorkload.state(1L));
        var first = returned.getFirst();
        returned.set(0, new UpdateAccount(first.id(), first.tenantId(), 2L, first.balance()));

        assertThrows(IllegalStateException.class, () -> BatchUpdateWorkload.checksum(requested, returned));
    }
}
