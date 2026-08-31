package no.beint.vev.benchmark;

import no.beint.vev.Batch;
import no.beint.vev.MutationResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class BatchUpdateWorkload {
    static final int SIZE = 32;
    static final int TENANT_ID = 7;

    private BatchUpdateWorkload() {
    }

    static Batch<UpdateAccount> request(long expectedVersion) {
        long resultingVersion = Math.addExact(expectedVersion, 1L);
        List<UpdateAccount> accounts = new ArrayList<>(SIZE);
        for (long id = 1; id <= SIZE; id++) {
            accounts.add(new UpdateAccount(
                    id,
                    TENANT_ID,
                    expectedVersion,
                    balance(id, resultingVersion)));
        }
        return Batch.copyOf(accounts);
    }

    static List<UpdateAccount> state(long version) {
        List<UpdateAccount> accounts = new ArrayList<>(SIZE);
        for (long id = 1; id <= SIZE; id++) {
            accounts.add(new UpdateAccount(
                    id,
                    TENANT_ID,
                    version,
                    balance(id, version)));
        }
        return List.copyOf(accounts);
    }

    static long checksum(
            Batch<UpdateAccount> requested,
            Batch<MutationResult.Applied<BenchmarkModelVev.Model, UpdateAccount, Long, Long>> applied) {
        if (requested.size() != SIZE || applied.size() != SIZE) {
            throw new IllegalStateException("Vev updateMultiple must return exactly 32 applied outcomes");
        }
        long checksum = mix(1L, SIZE);
        for (int index = 0; index < SIZE; index++) {
            UpdateAccount expected = requested.get(index);
            MutationResult.Applied<BenchmarkModelVev.Model, UpdateAccount, Long, Long> result = applied.get(index);
            long expectedVersion = expected.version();
            long resultingVersion = Math.addExact(expectedVersion, 1L);
            UpdateAccount actual = result.entity();
            requirePosition(index, expected);
            if (result.key().entityType() != UpdateAccountVev.INSTANCE
                    || !result.key().value().equals(expected.id())
                    || !result.expectedVersion().equals(expectedVersion)
                    || !result.version().equals(resultingVersion)) {
                throw new IllegalStateException("Vev updateMultiple returned mismatched outcome metadata at " + index);
            }
            requireTransition(expected, actual, resultingVersion, index);
            checksum = mixOutcome(checksum, index, expectedVersion, actual);
        }
        return mix(checksum, applied.size());
    }

    static long checksum(Batch<UpdateAccount> requested, List<UpdateAccount> returned) {
        if (requested.size() != SIZE || returned.size() != SIZE) {
            throw new IllegalStateException("Raw update must return exactly 32 rows");
        }
        long checksum = mix(1L, SIZE);
        for (int index = 0; index < SIZE; index++) {
            UpdateAccount expected = requested.get(index);
            UpdateAccount actual = returned.get(index);
            long expectedVersion = expected.version();
            long resultingVersion = Math.addExact(expectedVersion, 1L);
            requirePosition(index, expected);
            requireTransition(expected, actual, resultingVersion, index);
            checksum = mixOutcome(checksum, index, expectedVersion, actual);
        }
        return mix(checksum, returned.size());
    }

    static long stateChecksum(long version, List<UpdateAccount> accounts) {
        if (accounts.size() != SIZE) {
            throw new IllegalStateException("Update fixture must contain exactly 32 rows");
        }
        long checksum = mix(1L, SIZE);
        for (int index = 0; index < SIZE; index++) {
            UpdateAccount account = accounts.get(index);
            long expectedId = index + 1L;
            if (!account.id().equals(expectedId)
                    || !account.tenantId().equals(TENANT_ID)
                    || !account.version().equals(version)
                    || !account.balance().equals(balance(expectedId, version))) {
                throw new IllegalStateException("Update fixture state mismatch at " + index);
            }
            checksum = mixEntity(checksum, account);
        }
        return mix(checksum, accounts.size());
    }

    static BigDecimal balance(long id, long version) {
        long alternatingBase = (version & 1L) == 0L ? 200_000L : 100_000L;
        return BigDecimal.valueOf(Math.addExact(alternatingBase, id), 2).setScale(4);
    }

    private static void requirePosition(int index, UpdateAccount account) {
        long expectedId = index + 1L;
        if (!account.id().equals(expectedId)
                || !account.tenantId().equals(TENANT_ID)) {
            throw new IllegalStateException("Update batch identity or order mismatch at " + index);
        }
    }

    private static void requireTransition(
            UpdateAccount expected,
            UpdateAccount actual,
            long resultingVersion,
            int index) {
        if (!actual.id().equals(expected.id())
                || !actual.tenantId().equals(expected.tenantId())
                || !actual.version().equals(resultingVersion)
                || !actual.balance().equals(expected.balance())) {
            throw new IllegalStateException("Update batch state transition mismatch at " + index);
        }
    }

    private static long mixOutcome(long checksum, int index, long expectedVersion, UpdateAccount actual) {
        checksum = mix(checksum, index + 1L);
        checksum = mix(checksum, actual.id());
        checksum = mix(checksum, expectedVersion);
        checksum = mix(checksum, actual.version());
        return mixEntity(checksum, actual);
    }

    private static long mixEntity(long checksum, UpdateAccount account) {
        checksum = mix(checksum, account.id());
        checksum = mix(checksum, account.tenantId());
        checksum = mix(checksum, account.version());
        checksum = mix(checksum, account.balance().unscaledValue().longValueExact());
        return mix(checksum, account.balance().scale());
    }

    private static long mix(long checksum, long value) {
        return checksum * 31L + value;
    }
}
