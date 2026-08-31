package no.beint.vev.benchmark.hibernate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class BatchUpdateWorkload {
    static final int SIZE = 32;

    private BatchUpdateWorkload() {
    }

    static List<BenchmarkUpdateAccount> request(long expectedVersion) {
        long resultingVersion = Math.addExact(expectedVersion, 1L);
        List<BenchmarkUpdateAccount> accounts = new ArrayList<>(SIZE);
        for (long id = 1; id <= SIZE; id++) {
            accounts.add(new BenchmarkUpdateAccount(
                    id,
                    BenchmarkDataset.TENANT_ID,
                    expectedVersion,
                    balance(id, resultingVersion)));
        }
        return List.copyOf(accounts);
    }

    static List<BenchmarkUpdateAccount> state(long version) {
        List<BenchmarkUpdateAccount> accounts = new ArrayList<>(SIZE);
        for (long id = 1; id <= SIZE; id++) {
            accounts.add(new BenchmarkUpdateAccount(
                    id,
                    BenchmarkDataset.TENANT_ID,
                    version,
                    balance(id, version)));
        }
        return List.copyOf(accounts);
    }

    static long checksum(long expectedVersion, List<BenchmarkUpdateAccount> accounts) {
        long resultingVersion = Math.addExact(expectedVersion, 1L);
        if (accounts.size() != SIZE) {
            throw new IllegalStateException("Hibernate updateMultiple must produce exactly 32 entity states");
        }
        long checksum = mix(1L, SIZE);
        for (int index = 0; index < SIZE; index++) {
            BenchmarkUpdateAccount account = accounts.get(index);
            long expectedId = index + 1L;
            if (!account.id().equals(expectedId)
                    || !account.tenantId().equals(BenchmarkDataset.TENANT_ID)
                    || !account.version().equals(resultingVersion)
                    || !account.balance().equals(balance(expectedId, resultingVersion))) {
                throw new IllegalStateException("Hibernate update batch state transition mismatch at " + index);
            }
            checksum = mixOutcome(checksum, index, expectedVersion, account);
        }
        return mix(checksum, accounts.size());
    }

    static long stateChecksum(long version, List<BenchmarkUpdateAccount> accounts) {
        if (accounts.size() != SIZE) {
            throw new IllegalStateException("Update fixture must contain exactly 32 rows");
        }
        long checksum = mix(1L, SIZE);
        for (int index = 0; index < SIZE; index++) {
            BenchmarkUpdateAccount account = accounts.get(index);
            long expectedId = index + 1L;
            if (!account.id().equals(expectedId)
                    || !account.tenantId().equals(BenchmarkDataset.TENANT_ID)
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

    private static long mixOutcome(
            long checksum,
            int index,
            long expectedVersion,
            BenchmarkUpdateAccount account) {
        checksum = mix(checksum, index + 1L);
        checksum = mix(checksum, account.id());
        checksum = mix(checksum, expectedVersion);
        checksum = mix(checksum, account.version());
        return mixEntity(checksum, account);
    }

    private static long mixEntity(long checksum, BenchmarkUpdateAccount account) {
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
