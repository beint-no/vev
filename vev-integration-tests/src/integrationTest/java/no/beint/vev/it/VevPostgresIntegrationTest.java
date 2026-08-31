package no.beint.vev.it;

import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.EntityAgent;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.FindOption;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Timeout;
import no.beint.vev.Batch;
import no.beint.vev.BoundedQuery;
import no.beint.vev.EntityLookup;
import no.beint.vev.ModelIdentity;
import no.beint.vev.MutationResult;
import no.beint.vev.QueryLimit;
import no.beint.vev.Rows;
import no.beint.vev.TenantAuthority;
import no.beint.vev.TenantScope;
import no.beint.vev.WriteEntities;
import no.beint.vev.jakarta.VevEntityAgents;
import no.beint.vev.pg.PgNullableIndex;
import no.beint.vev.pg.PgQueries;
import no.beint.vev.pg.PgVev;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.SAME_THREAD)
final class VevPostgresIntegrationTest {
    private static final TenantAuthority<IntegrationModelVev.Model, Integer> TENANT_AUTHORITY =
            IntegrationModelVev.newTenantAuthority();

    private static IntegrationDatabase database;
    private static PgVev<IntegrationModelVev.Model, Integer> vev;
    private static TenantScope<IntegrationModelVev.Model, Integer> TENANT_7;
    private static TenantScope<IntegrationModelVev.Model, Integer> TENANT_8;

    @BeforeAll
    static void initialize() throws SQLException {
        database = IntegrationDatabase.connect();
        database.initialize(IntegrationModelVev.IDENTITY.name(), IntegrationModelVev.IDENTITY.fingerprint());
        vev = new PgVev<>(database.applicationDataSource(), IntegrationModelVev.POSTGRES, TENANT_AUTHORITY);
        TENANT_7 = TENANT_AUTHORITY.scope(7);
        TENANT_8 = TENANT_AUTHORITY.scope(8);
    }

    @BeforeEach
    void truncate() throws SQLException {
        database.truncateAccounts();
    }

    @Test
    void orderedBatchReadsPreserveMissingRowsAndTenantIsolation() {
        UUID sharedId = id("shared");
        Account tenantSeven = insert(account(sharedId, 7, 0, "seven@example.test", "17.00"), TENANT_7);
        Account tenantEight = insert(account(sharedId, 8, 0, "eight@example.test", "18.00"), TENANT_8);

        assertEquals("seven@example.test", tenantSeven.email());
        assertEquals("eight@example.test", tenantEight.email());
        assertEquals("seven@example.test", find(sharedId, TENANT_7).email());
        assertEquals("eight@example.test", find(sharedId, TENANT_8).email());

        UUID missing = id("missing");
        Batch<EntityLookup<IntegrationModelVev.Model, Account, UUID>> lookups = vev.read(TENANT_7, transaction ->
                transaction.entities().findMultiple(AccountVev.INSTANCE, Batch.copyOf(List.of(sharedId, missing, sharedId))));

        assertEquals(3, lookups.size());
        Account first = foundAccount(lookups.get(0));
        assertInstanceOf(EntityLookup.Missing.class, lookups.get(1));
        Account third = foundAccount(lookups.get(2));
        assertEquals(sharedId, first.id());
        assertEquals(sharedId, third.id());
        assertNotSame(first, third);
    }

    @Test
    void batchInsertPreservesInputOrderAndRejectsDuplicateKeysAtomicallyBeforeSql() {
        Account third = account(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                7,
                0,
                "third@example.test",
                "3.0000");
        Account first = account(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                7,
                0,
                "first@example.test",
                "1.0000");
        Account second = account(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                7,
                0,
                "second@example.test",
                "2.0000");
        Batch<Account> input = Batch.copyOf(List.of(third, first, second));

        Batch<Account> inserted = vev.write(TENANT_7, transaction ->
                transaction.entities().insertMultiple(AccountVev.INSTANCE, input));

        assertEquals(input, inserted);
        assertEquals(List.of(third.id(), first.id(), second.id()),
                inserted.values().stream().map(Account::id).toList());

        UUID duplicateId = id("duplicate-batch-key");
        Account duplicateFirst = account(duplicateId, 7, 0, "duplicate-first@example.test", "4.0000");
        Account duplicateSecond = account(duplicateId, 7, 0, "duplicate-second@example.test", "5.0000");
        Account validAfterRejection = account(
                id("valid-after-duplicate-batch-key"),
                7,
                0,
                "valid-after-rejection@example.test",
                "6.0000");

        vev.write(TENANT_7, transaction -> {
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insertMultiple(
                    AccountVev.INSTANCE,
                    Batch.copyOf(List.of(duplicateFirst, duplicateSecond))));
            assertTrue(transaction.entities().find(AccountVev.INSTANCE.key(duplicateId)).isEmpty());
            transaction.entities().insert(AccountVev.INSTANCE, validAfterRejection);
            return null;
        });

        assertTrue(vev.read(TENANT_7, transaction ->
                transaction.entities().find(AccountVev.INSTANCE.key(duplicateId)).isEmpty()).booleanValue());
        assertEquals(validAfterRejection, find(validAfterRejection.id(), TENANT_7));
    }

    @Test
    void setBasedBatchUpdateUsesOneStatementAndReturnsExactSnapshotsInInputOrder() {
        Account third = account(
                UUID.fromString("00000000-0000-0000-0000-000000000013"),
                7,
                0,
                "third-before@example.test",
                "3.0000");
        Account first = account(
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                7,
                0,
                "first-before@example.test",
                "1.0000");
        Account second = account(
                UUID.fromString("00000000-0000-0000-0000-000000000012"),
                7,
                0,
                "second-before@example.test",
                "2.0000");
        Batch<Account> originals = Batch.copyOf(List.of(third, first, second));
        vev.write(TENANT_7, transaction ->
                transaction.entities().insertMultiple(AccountVev.INSTANCE, originals));

        Batch<Account> requested = Batch.copyOf(List.of(
                account(third.id(), 7, 0, "third-after@example.test", "13.0000"),
                account(first.id(), 7, 0, null, "11.0000"),
                account(second.id(), 7, 0, "second-after@example.test", "12.0000")));
        AtomicInteger batchUpdateStatements = new AtomicInteger();
        TenantAuthority<IntegrationModelVev.Model, Integer> authority =
                IntegrationModelVev.newTenantAuthority();
        PgVev<IntegrationModelVev.Model, Integer> countedRuntime = new PgVev<>(
                batchUpdateCountingDataSource(database.applicationDataSource(), batchUpdateStatements),
                IntegrationModelVev.POSTGRES,
                authority);
        TenantScope<IntegrationModelVev.Model, Integer> tenant = authority.scope(7);

        Batch<MutationResult.Applied<IntegrationModelVev.Model, Account, UUID, Long>> applied =
                countedRuntime.write(tenant, transaction ->
                        transaction.entities().updateMultiple(AccountVev.INSTANCE, requested));

        assertEquals(1, batchUpdateStatements.get());
        assertEquals(requested.size(), applied.size());
        for (int index = 0; index < requested.size(); index++) {
            Account input = requested.get(index);
            Account expected = new Account(
                    input.id(), input.tenantId(), 1L, input.email(), input.balance());
            MutationResult.Applied<IntegrationModelVev.Model, Account, UUID, Long> result = applied.get(index);
            assertSame(AccountVev.INSTANCE, result.key().entityType());
            assertEquals(input.id(), result.key().value());
            assertEquals(0L, result.expectedVersion());
            assertEquals(1L, result.version());
            assertEquals(expected, result.entity());
            assertEquals(expected, find(input.id(), TENANT_7));
        }
    }

    @Test
    void staleOrMissingBatchMemberLeavesEveryRowUnchanged() {
        Account first = account(id("batch-update-atomic-first"), 7, 0, "first@example.test", "1.0000");
        Account second = account(id("batch-update-atomic-second"), 7, 0, "second@example.test", "2.0000");
        vev.write(TENANT_7, transaction -> transaction.entities().insertMultiple(
                AccountVev.INSTANCE,
                Batch.copyOf(List.of(first, second))));
        Account validFirst = account(first.id(), 7, 0, "first-changed@example.test", "11.0000");
        Account staleSecond = account(second.id(), 7, 1, "second-stale@example.test", "12.0000");

        IllegalStateException staleFailure = assertThrows(IllegalStateException.class, () ->
                vev.write(TENANT_7, transaction -> transaction.entities().updateMultiple(
                        AccountVev.INSTANCE,
                        Batch.copyOf(List.of(validFirst, staleSecond)))));

        assertEquals(
                "Update batch was rejected atomically because one entity was stale or missing",
                staleFailure.getMessage());
        assertEquals(first, find(first.id(), TENANT_7));
        assertEquals(second, find(second.id(), TENANT_7));

        Account missing = account(
                id("batch-update-atomic-missing"), 7, 0, "missing@example.test", "13.0000");
        IllegalStateException missingFailure = assertThrows(IllegalStateException.class, () ->
                vev.write(TENANT_7, transaction -> transaction.entities().updateMultiple(
                        AccountVev.INSTANCE,
                        Batch.copyOf(List.of(validFirst, missing)))));

        assertEquals(
                "Update batch was rejected atomically because one entity was stale or missing",
                missingFailure.getMessage());
        assertEquals(first, find(first.id(), TENANT_7));
        assertEquals(second, find(second.id(), TENANT_7));
        assertTrue(vev.read(TENANT_7, transaction ->
                transaction.entities().find(AccountVev.INSTANCE.key(missing.id())).isEmpty()).booleanValue());
    }

    @Test
    void duplicateBatchUpdateKeysAreRejectedBeforeSqlWithoutPoisoning() {
        Account original = insert(account(
                id("batch-update-duplicate"), 7, 0, "before@example.test", "1.0000"), TENANT_7);
        Account duplicateFirst = account(original.id(), 7, 0, "first@example.test", "2.0000");
        Account duplicateSecond = account(original.id(), 7, 0, "second@example.test", "3.0000");
        AtomicInteger batchUpdateStatements = new AtomicInteger();
        TenantAuthority<IntegrationModelVev.Model, Integer> authority =
                IntegrationModelVev.newTenantAuthority();
        PgVev<IntegrationModelVev.Model, Integer> countedRuntime = new PgVev<>(
                batchUpdateCountingDataSource(database.applicationDataSource(), batchUpdateStatements),
                IntegrationModelVev.POSTGRES,
                authority);
        TenantScope<IntegrationModelVev.Model, Integer> tenant = authority.scope(7);

        countedRuntime.write(tenant, transaction -> {
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().updateMultiple(
                    AccountVev.INSTANCE,
                    Batch.copyOf(List.of(duplicateFirst, duplicateSecond))));
            assertEquals(0, batchUpdateStatements.get());
            assertEquals(original, transaction.entities().find(AccountVev.INSTANCE.key(original.id())).orElseThrow());
            return null;
        });

        assertEquals(0, batchUpdateStatements.get());
        assertEquals(original, find(original.id(), TENANT_7));
    }

    @Test
    void caughtRejectedBatchPoisonsAndRollsBackEarlierWrites() {
        Account first = account(id("caught-batch-first"), 7, 0, "first@example.test", "1.0000");
        Account second = account(id("caught-batch-second"), 7, 0, "second@example.test", "2.0000");
        vev.write(TENANT_7, transaction -> transaction.entities().insertMultiple(
                AccountVev.INSTANCE,
                Batch.copyOf(List.of(first, second))));
        Account earlierWrite = account(
                id("caught-batch-earlier-write"), 7, 0, "earlier@example.test", "3.0000");
        Account validFirst = account(first.id(), 7, 0, "first-changed@example.test", "11.0000");
        Account staleSecond = account(second.id(), 7, 1, "second-stale@example.test", "12.0000");

        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, transaction -> {
            transaction.entities().insert(AccountVev.INSTANCE, earlierWrite);
            assertThrows(IllegalStateException.class, () -> transaction.entities().updateMultiple(
                    AccountVev.INSTANCE,
                    Batch.copyOf(List.of(validFirst, staleSecond))));
            assertThrows(IllegalStateException.class, () ->
                    transaction.entities().find(AccountVev.INSTANCE.key(first.id())));
            return null;
        }));

        assertEquals(first, find(first.id(), TENANT_7));
        assertEquals(second, find(second.id(), TENANT_7));
        assertTrue(vev.read(TENANT_7, transaction ->
                transaction.entities().find(AccountVev.INSTANCE.key(earlierWrite.id())).isEmpty()).booleanValue());
    }

    @Test
    void jdbcArrayCleanupFailurePoisonsAndRollsBackSuccessfulBatchUpdate() {
        Account first = account(id("array-cleanup-first"), 7, 0, "first@example.test", "1.0000");
        Account second = account(id("array-cleanup-second"), 7, 0, "second@example.test", "2.0000");
        vev.write(TENANT_7, transaction -> transaction.entities().insertMultiple(
                AccountVev.INSTANCE,
                Batch.copyOf(List.of(first, second))));
        Batch<Account> requested = Batch.copyOf(List.of(
                account(first.id(), 7, 0, "first-changed@example.test", "11.0000"),
                account(second.id(), 7, 0, "second-changed@example.test", "12.0000")));
        AtomicInteger cleanupFailures = new AtomicInteger();
        TenantAuthority<IntegrationModelVev.Model, Integer> authority =
                IntegrationModelVev.newTenantAuthority();
        PgVev<IntegrationModelVev.Model, Integer> cleanupFailureRuntime = new PgVev<>(
                arrayCleanupFailureDataSource(database.applicationDataSource(), cleanupFailures),
                IntegrationModelVev.POSTGRES,
                authority);
        TenantScope<IntegrationModelVev.Model, Integer> tenant = authority.scope(7);

        assertThrows(IllegalStateException.class, () -> cleanupFailureRuntime.write(tenant, transaction ->
                transaction.entities().updateMultiple(AccountVev.INSTANCE, requested)));

        assertEquals(1, cleanupFailures.get());
        assertEquals(first, find(first.id(), TENANT_7));
        assertEquals(second, find(second.id(), TENANT_7));
    }

    @Test
    void concurrentRowChangeBetweenBatchPreflightAndLockFailsClosed() throws Exception {
        Account first = account(id("concurrent-batch-first"), 7, 0, "first@example.test", "1.0000");
        Account second = account(id("concurrent-batch-second"), 7, 0, "second@example.test", "2.0000");
        vev.write(TENANT_7, transaction -> transaction.entities().insertMultiple(
                AccountVev.INSTANCE,
                Batch.copyOf(List.of(first, second))));
        Batch<Account> requested = Batch.copyOf(List.of(
                account(first.id(), 7, 0, "first-changed@example.test", "11.0000"),
                account(second.id(), 7, 0, "second-changed@example.test", "12.0000")));

        CompletableFuture<Throwable> updateOutcome;
        try (Connection blocker = database.openAdminTransaction();
             var processIdStatement = blocker.prepareStatement("SELECT pg_catalog.pg_backend_pid()");
             var statement = blocker.prepareStatement("""
                     UPDATE vev_it.account
                        SET version = version + 1
                     WHERE tenant_id = ?
                        AND id = ?
                        AND version = ?
                     """)) {
            int blockerProcessId;
            try (var resultSet = processIdStatement.executeQuery()) {
                assertTrue(resultSet.next());
                blockerProcessId = resultSet.getInt(1);
            }
            statement.setInt(1, 7);
            statement.setObject(2, second.id());
            statement.setLong(3, 0L);
            assertEquals(1, statement.executeUpdate());
            updateOutcome = CompletableFuture.supplyAsync(() -> {
                try {
                    vev.write(TENANT_7, transaction ->
                            transaction.entities().updateMultiple(AccountVev.INSTANCE, requested));
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            }, command -> Thread.ofVirtual().name("vev-concurrent-batch-update").start(command));
            database.awaitBlockedBatchUpdate(blockerProcessId);
            blocker.commit();
        }

        IllegalStateException serializationFailure = assertInstanceOf(
                IllegalStateException.class,
                updateOutcome.get(10, TimeUnit.SECONDS));
        assertTrue(serializationFailure.getMessage().contains("SQLSTATE 40001"));
        assertEquals(first, find(first.id(), TENANT_7));
        assertEquals(
                account(second.id(), 7, 1, second.email(), second.balance().toPlainString()),
                find(second.id(), TENANT_7));
    }

    @Test
    void scopeFromAnotherAuthorityIsRejectedBeforeAValidTransaction() {
        TenantAuthority<IntegrationModelVev.Model, Integer> foreignAuthority =
                IntegrationModelVev.newTenantAuthority();
        new PgVev<>(database.applicationDataSource(), IntegrationModelVev.POSTGRES, foreignAuthority);
        TenantScope<IntegrationModelVev.Model, Integer> foreignScope = foreignAuthority.scope(7);

        assertThrows(IllegalArgumentException.class, () -> vev.read(foreignScope, transaction -> null));
        assertThrows(IllegalArgumentException.class, () ->
                VevEntityAgents.callInTransaction(vev, foreignScope, agent -> null));
        assertEquals(Integer.valueOf(7), vev.read(TENANT_7, transaction -> transaction.tenant().tenantId()));
    }

    @Test
    void modelIdentityAndOneRuntimeClaimAreEnforcedBeforeConnectionAcquisition() {
        TenantAuthority<WrongModel, Integer> wrongIdentity = TenantAuthority.create(
                WrongModel.class,
                new ModelIdentity("wrong-model", "sha256:" + "f".repeat(64)),
                Integer.class);
        TenantAuthority<IntegrationModelVev.Model, Integer> forgedAuthority = eraseModelType(wrongIdentity);

        assertThrows(IllegalArgumentException.class, () ->
                new PgVev<>(connectionForbiddenDataSource(), IntegrationModelVev.POSTGRES, forgedAuthority));
        assertThrows(IllegalStateException.class, () ->
                new PgVev<>(connectionForbiddenDataSource(), IntegrationModelVev.POSTGRES, TENANT_AUTHORITY));
    }

    @Test
    void failedDatabaseVerificationReleasesTheAuthorityReservation() {
        TenantAuthority<IntegrationModelVev.Model, Integer> authority =
                IntegrationModelVev.newTenantAuthority();

        assertThrows(IllegalStateException.class, () ->
                new PgVev<>(connectionFailureDataSource(), IntegrationModelVev.POSTGRES, authority));

        PgVev<IntegrationModelVev.Model, Integer> recovered =
                new PgVev<>(database.applicationDataSource(), IntegrationModelVev.POSTGRES, authority);
        TenantScope<IntegrationModelVev.Model, Integer> recoveredScope = authority.scope(7);
        assertEquals(Integer.valueOf(7), recovered.read(
                recoveredScope,
                transaction -> transaction.tenant().tenantId()));
    }

    @Test
    void successfulCommitIsReturnedAndPersistedWhenConnectionCloseFailsAfterCommit() {
        TenantAuthority<IntegrationModelVev.Model, Integer> authority =
                IntegrationModelVev.newTenantAuthority();
        PgVev<IntegrationModelVev.Model, Integer> faultRuntime = new PgVev<>(
                new LifecycleFaultDataSource(
                        database.applicationDataSource(),
                        LifecycleFaultDataSource.Mode.CLOSE_AFTER_COMMIT),
                IntegrationModelVev.POSTGRES,
                authority);
        TenantScope<IntegrationModelVev.Model, Integer> tenant = authority.scope(7);
        Account expected = account(
                id("close-after-commit"), 7, 0, "close-after-commit@example.test", "4.0000");

        Account returned = faultRuntime.write(tenant, transaction ->
                transaction.entities().insert(AccountVev.INSTANCE, expected));

        assertEquals(expected, returned);
        assertEquals(expected, find(expected.id(), TENANT_7));
    }

    @Test
    void commitFailureAfterDelegateSuccessIsIndeterminateAndNeverRetried() {
        TenantAuthority<IntegrationModelVev.Model, Integer> authority =
                IntegrationModelVev.newTenantAuthority();
        PgVev<IntegrationModelVev.Model, Integer> faultRuntime = new PgVev<>(
                new LifecycleFaultDataSource(
                        database.applicationDataSource(),
                        LifecycleFaultDataSource.Mode.COMMIT_AFTER_SUCCESS),
                IntegrationModelVev.POSTGRES,
                authority);
        TenantScope<IntegrationModelVev.Model, Integer> tenant = authority.scope(7);
        Account expected = account(
                id("commit-after-success"), 7, 0, "commit-after-success@example.test", "5.0000");
        AtomicInteger attempts = new AtomicInteger();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                faultRuntime.write(tenant, transaction -> {
                    attempts.incrementAndGet();
                    return transaction.entities().insert(AccountVev.INSTANCE, expected);
                }));

        assertEquals(
                "PostgreSQL commit outcome is indeterminate; the operation must not be retried automatically [SQLSTATE 08006]",
                failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains("sensitive-fixture-value"));
        assertEquals(1, attempts.get());
        assertEquals(expected, find(expected.id(), TENANT_7));
    }

    @Test
    void bootstrapResourceFailuresAreSanitizedAndReleaseTheAuthorityReservation() throws SQLException {
        TenantAuthority<IntegrationModelVev.Model, Integer> authority =
                IntegrationModelVev.newTenantAuthority();
        database.setFingerprint(IntegrationModelVev.IDENTITY.name(), "wrong-fingerprint");
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                    new PgVev<>(
                            new LifecycleFaultDataSource(
                                    database.applicationDataSource(),
                                    LifecycleFaultDataSource.Mode.FINGERPRINT_RESOURCE_AND_CONNECTION_CLOSE),
                            IntegrationModelVev.POSTGRES,
                            authority));

            assertEquals("Vev rejected PostgreSQL during fingerprint value verification", failure.getMessage());
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            assertFalse(failure.toString().contains("sensitive-fixture-value"));
        } finally {
            database.setFingerprint(
                    IntegrationModelVev.IDENTITY.name(),
                    IntegrationModelVev.IDENTITY.fingerprint());
        }

        PgVev<IntegrationModelVev.Model, Integer> recovered =
                new PgVev<>(database.applicationDataSource(), IntegrationModelVev.POSTGRES, authority);
        TenantScope<IntegrationModelVev.Model, Integer> tenant = authority.scope(7);
        assertEquals(Integer.valueOf(7), recovered.read(
                tenant,
                transaction -> transaction.tenant().tenantId()));
    }

    @Test
    void verifiedRuntimeRejectsFingerprintDriftBeforeApplicationWork() throws SQLException {
        AtomicInteger calls = new AtomicInteger();
        database.setFingerprint(IntegrationModelVev.IDENTITY.name(), "wrong-fingerprint");
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                    vev.read(TENANT_7, transaction -> {
                        calls.incrementAndGet();
                        return null;
                    }));

            assertEquals("Vev rejected the PostgreSQL transaction context", failure.getMessage());
            assertEquals(0, calls.get());
        } finally {
            database.setFingerprint(
                    IntegrationModelVev.IDENTITY.name(),
                    IntegrationModelVev.IDENTITY.fingerprint());
        }

        assertEquals(Integer.valueOf(7), vev.read(
                TENANT_7,
                transaction -> transaction.tenant().tenantId()));
    }

    @Test
    void optimisticMutationsReturnExhaustiveResultsWithoutPoisoningOnConflict() {
        UUID accountId = id("optimistic");
        Account original = insert(account(accountId, 7, 0, "first@example.test", "10.00"), TENANT_7);

        vev.write(TENANT_7, transaction -> {
            Account changed = new Account(
                    original.id(), original.tenantId(), original.version(), "second@example.test", original.balance());
            MutationResult<IntegrationModelVev.Model, Account, UUID, Long> applied =
                    transaction.entities().update(AccountVev.INSTANCE, changed);
            if (!(applied instanceof MutationResult.Applied<?, ?, ?, ?> update)) {
                throw new AssertionError("Expected applied update");
            }
            assertEquals(1L, update.version());
            assertEquals("second@example.test", assertInstanceOf(Account.class, update.entity()).email());

            MutationResult<IntegrationModelVev.Model, Account, UUID, Long> stale =
                    transaction.entities().update(AccountVev.INSTANCE, changed);
            assertInstanceOf(MutationResult.Conflict.class, stale);
            assertTrue(transaction.entities().find(AccountVev.INSTANCE.key(accountId)).isPresent());
            return null;
        });
    }

    @Test
    void nullableEmailRoundTripsThroughInsertFindAndUpdate() {
        UUID accountId = id("nullable-email");
        Account inserted = insert(account(accountId, 7, 0, null, "1.0000"), TENANT_7);

        assertNull(inserted.email());
        assertNull(find(accountId, TENANT_7).email());

        Account updated = vev.write(TENANT_7, transaction -> {
            Account changed = new Account(
                    inserted.id(), inserted.tenantId(), inserted.version(), null, new BigDecimal("2.0000"));
            MutationResult<IntegrationModelVev.Model, Account, UUID, Long> result =
                    transaction.entities().update(AccountVev.INSTANCE, changed);
            MutationResult.Applied<?, ?, ?, ?> applied = assertInstanceOf(MutationResult.Applied.class, result);
            return assertInstanceOf(Account.class, applied.entity());
        });

        assertEquals(1L, updated.version());
        assertNull(updated.email());
        assertNull(find(accountId, TENANT_7).email());
    }

    @Test
    void boundedScalarViolationsFailBeforeSqlWithoutPoisoningTheTransaction() {
        String malformedUnicode = String.valueOf(Character.MIN_HIGH_SURROGATE);
        BigDecimal validBalance = new BigDecimal("1.0000");
        Instant validInstant = Instant.parse("2026-08-30T12:34:56.123456Z");
        LocalDate validDate = LocalDate.parse("2026-08-30");
        LocalDateTime validLocalTimestamp = LocalDateTime.parse("2026-08-30T12:34:56.123456");

        vev.write(TENANT_7, transaction -> {
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AccountVev.INSTANCE,
                    new Account(id("overlong-email"), 7, 0L, "x".repeat(256), validBalance)));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AccountVev.INSTANCE,
                    new Account(id("malformed-email"), 7, 0L, malformedUnicode, validBalance)));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AccountVev.INSTANCE,
                    new Account(id("nul-email"), 7, 0L, "nul" + Character.MIN_VALUE, validBalance)));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AccountVev.INSTANCE,
                    new Account(id("wrong-scale"), 7, 0L, "scale@example.test", new BigDecimal("1.000"))));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AccountVev.INSTANCE,
                    new Account(
                            id("decimal-subclass"),
                            7,
                            0L,
                            "subclass@example.test",
                            new HostileBigDecimal("1.0000"))));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AccountVev.INSTANCE,
                    new Account(
                            id("excessive-precision"),
                            7,
                            0L,
                            "precision@example.test",
                            new BigDecimal("1234567890123456.0000"))));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AuditEventVev.INSTANCE,
                    new AuditEvent(
                            id("sub-microsecond-instant"),
                            7,
                            Instant.parse("2026-08-30T12:34:56.123456789Z"),
                            validLocalTimestamp,
                            validDate,
                            "SUB_MICROSECOND_INSTANT")));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AuditEventVev.INSTANCE,
                    new AuditEvent(
                            id("sub-microsecond-local-time"),
                            7,
                            validInstant,
                            LocalDateTime.parse("2026-08-30T12:34:56.123456789"),
                            validDate,
                            "SUB_MICROSECOND_LOCAL_TIME")));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AuditEventVev.INSTANCE,
                    new AuditEvent(
                            id("minimum-instant"), 7, Instant.MIN, validLocalTimestamp, validDate, "MINIMUM_INSTANT")));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AuditEventVev.INSTANCE,
                    new AuditEvent(
                            id("maximum-instant"), 7, Instant.MAX, validLocalTimestamp, validDate, "MAXIMUM_INSTANT")));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AuditEventVev.INSTANCE,
                    new AuditEvent(
                            id("minimum-local-time"), 7, validInstant, LocalDateTime.MIN, validDate, "MINIMUM_LOCAL_TIME")));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AuditEventVev.INSTANCE,
                    new AuditEvent(
                            id("maximum-local-time"), 7, validInstant, LocalDateTime.MAX, validDate, "MAXIMUM_LOCAL_TIME")));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AuditEventVev.INSTANCE,
                    new AuditEvent(
                            id("minimum-date"), 7, validInstant, validLocalTimestamp, LocalDate.MIN, "MINIMUM_DATE")));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AuditEventVev.INSTANCE,
                    new AuditEvent(
                            id("maximum-date"), 7, validInstant, validLocalTimestamp, LocalDate.MAX, "MAXIMUM_DATE")));

            Account validAccount = transaction.entities().insert(
                    AccountVev.INSTANCE,
                    account(id("valid-after-bounded-rejections"), 7, 0, null, "2.0000"));
            AuditEvent validAudit = transaction.entities().insert(
                    AuditEventVev.INSTANCE,
                    new AuditEvent(
                            id("valid-audit-after-bounded-rejections"),
                            7,
                            validInstant,
                            validLocalTimestamp,
                            validDate,
                            "VALID_AFTER_REJECTIONS"));
            assertNull(validAccount.email());
            assertEquals(validLocalTimestamp, validAudit.localOccurredAt());
            return null;
        });

        assertNull(find(id("valid-after-bounded-rejections"), TENANT_7).email());
        boolean validAuditPresent = vev.read(TENANT_7, transaction -> transaction.entities()
                .find(AuditEventVev.INSTANCE.key(id("valid-audit-after-bounded-rejections")))
                .isPresent());
        assertTrue(validAuditPresent);
    }

    @Test
    void crossTenantEntityStateFailsBeforeSqlWithoutPoisoningTheTransaction() {
        UUID accountId = id("cross-tenant-state");

        vev.write(TENANT_7, transaction -> {
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AccountVev.INSTANCE,
                    account(accountId, 8, 0, "wrong-tenant@example.test", "1.00")));
            assertTrue(transaction.entities().find(AccountVev.INSTANCE.key(accountId)).isEmpty());
            return null;
        });

        assertFalse(vev.read(TENANT_8, transaction ->
                transaction.entities().find(AccountVev.INSTANCE.key(accountId))).isPresent());
    }

    @Test
    void invalidInitialVersionFailsBeforeSqlWithoutPoisoningTheTransaction() {
        UUID rejectedId = id("invalid-initial-version");
        UUID validId = id("valid-after-invalid-version");

        vev.write(TENANT_7, transaction -> {
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().insert(
                    AccountVev.INSTANCE,
                    account(rejectedId, 7, 3, "invalid-version@example.test", "1.00")));
            Account inserted = transaction.entities().insert(
                    AccountVev.INSTANCE,
                    account(validId, 7, 0, "valid-version@example.test", "1.00"));
            assertEquals(0L, inserted.version());
            return null;
        });

        assertFalse(vev.read(TENANT_7, transaction ->
                transaction.entities().find(AccountVev.INSTANCE.key(rejectedId))).isPresent());
        assertTrue(vev.read(TENANT_7, transaction ->
                transaction.entities().find(AccountVev.INSTANCE.key(validId))).isPresent());
    }

    @Test
    void versionOverflowFailsBeforeSqlWithoutPoisoningTheTransaction() {
        UUID overflowId = id("version-overflow");
        UUID validId = id("valid-after-version-overflow");

        vev.write(TENANT_7, transaction -> {
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().update(
                    AccountVev.INSTANCE,
                    account(overflowId, 7, Long.MAX_VALUE, "overflow@example.test", "1.0000")));
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().updateMultiple(
                    AccountVev.INSTANCE,
                    Batch.copyOf(List.of(
                            account(overflowId, 7, Long.MAX_VALUE, "batch-overflow@example.test", "1.0000"),
                            account(id("batch-after-overflow"), 7, 0, "batch-valid@example.test", "1.0000")))));
            Account inserted = transaction.entities().insert(
                    AccountVev.INSTANCE,
                    account(validId, 7, 0, "valid-after-overflow@example.test", "2.0000"));
            assertEquals(0L, inserted.version());
            return null;
        });

        assertFalse(vev.read(TENANT_7, transaction ->
                transaction.entities().find(AccountVev.INSTANCE.key(overflowId))).isPresent());
        assertTrue(vev.read(TENANT_7, transaction ->
                transaction.entities().find(AccountVev.INSTANCE.key(validId))).isPresent());
    }

    @Test
    void readOnlyTransactionRejectsWritesEvenAfterAnUnsafeInterfaceCast() {
        UUID accountId = id("read-only-cast");

        vev.read(TENANT_7, transaction -> {
            WriteEntities<IntegrationModelVev.Model> writeEntities =
                    (WriteEntities<IntegrationModelVev.Model>) transaction.entities();
            assertThrows(IllegalStateException.class, () -> writeEntities.insert(
                    AccountVev.INSTANCE,
                    account(accountId, 7, 0, "read-only@example.test", "1.00")));
            assertTrue(transaction.entities().find(AccountVev.INSTANCE.key(accountId)).isEmpty());
            return null;
        });

        assertFalse(vev.read(TENANT_7, transaction ->
                transaction.entities().find(AccountVev.INSTANCE.key(accountId))).isPresent());
    }

    @Test
    void nestedTransactionsAreRejectedBeforeOpeningAnIndependentBoundary() {
        UUID accountId = id("nested-transaction");

        vev.write(TENANT_7, transaction -> {
            assertThrows(IllegalStateException.class, () ->
                    vev.write(TENANT_7, nested -> null));
            transaction.entities().insert(
                    AccountVev.INSTANCE,
                    account(accountId, 7, 0, "outer-remains-usable@example.test", "1.00"));
            return null;
        });

        assertTrue(vev.read(TENANT_7, transaction ->
                transaction.entities().find(AccountVev.INSTANCE.key(accountId))).isPresent());
    }

    @Test
    void boundedScanUsesTheGeneratedPlanAndRejectsForgedQueries() {
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID thirdId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID fourthId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        UUID fifthId = UUID.fromString("00000000-0000-0000-0000-000000000005");
        insert(account(firstId, 7, 0, "first@example.test", "1.00"), TENANT_7);
        insert(account(secondId, 7, 0, "second@example.test", "2.00"), TENANT_7);
        insert(account(thirdId, 7, 0, "third@example.test", "3.00"), TENANT_7);
        insert(account(fourthId, 7, 0, "fourth@example.test", "4.00"), TENANT_7);
        insert(account(fifthId, 7, 0, "fifth@example.test", "5.00"), TENANT_7);
        insert(account(firstId, 8, 0, "tenant-eight-first@example.test", "8.00"), TENANT_8);
        insert(account(thirdId, 8, 0, "tenant-eight-third@example.test", "8.00"), TENANT_8);

        vev.read(TENANT_7, transaction -> {
            Rows<Account> rows = transaction.entities().many(
                    PgQueries.scanById(AccountVev.INSTANCE, new QueryLimit(2)));
            assertEquals(List.of(firstId, secondId), rows.values().stream().map(Account::id).toList());
            assertTrue(rows.hasMore());

            Rows<Account> remaining = transaction.entities().many(
                    PgQueries.scanByIdAfter(AccountVev.INSTANCE.key(secondId), new QueryLimit(2)));
            assertEquals(List.of(thirdId, fourthId), remaining.values().stream().map(Account::id).toList());
            assertTrue(remaining.hasMore());

            Rows<Account> finalPage = transaction.entities().many(
                    PgQueries.scanByIdAfter(AccountVev.INSTANCE.key(fourthId), new QueryLimit(2)));
            assertEquals(List.of(fifthId), finalPage.values().stream().map(Account::id).toList());
            assertFalse(finalPage.hasMore());

            Rows<Account> emptyPage = transaction.entities().many(
                    PgQueries.scanByIdAfter(AccountVev.INSTANCE.key(fifthId), new QueryLimit(2)));
            assertTrue(emptyPage.values().isEmpty());
            assertFalse(emptyPage.hasMore());

            return null;
        });

        vev.read(TENANT_8, transaction -> {
            Rows<Account> tenantRelativeContinuation = transaction.entities().many(
                    PgQueries.scanByIdAfter(AccountVev.INSTANCE.key(firstId), new QueryLimit(2)));
            assertEquals(
                    List.of(thirdId),
                    tenantRelativeContinuation.values().stream().map(Account::id).toList());
            assertFalse(tenantRelativeContinuation.hasMore());
            assertEquals(8, tenantRelativeContinuation.values().getFirst().tenantId());

            BoundedQuery<IntegrationModelVev.Model, Account> forged = new BoundedQuery<>() {
                @Override
                public ModelIdentity modelIdentity() {
                    return AccountVev.INSTANCE.modelIdentity();
                }

                @Override
                public Class<Account> resultType() {
                    return Account.class;
                }

                @Override
                public QueryLimit limit() {
                    return new QueryLimit(2);
                }
            };
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().many(forged));
            assertTrue(transaction.entities().find(AccountVev.INSTANCE.key(firstId)).isPresent());
            return null;
        });
    }

    @Test
    void indexedEqualityQueriesAreBoundedTenantScopedAndKeysetSafe() {
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID thirdId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID fourthId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        UUID fifthId = UUID.fromString("00000000-0000-0000-0000-000000000005");
        String sharedEmail = "shared@example.test";

        vev.write(TENANT_7, transaction -> {
            transaction.entities().insertMultiple(AccountVev.INSTANCE, Batch.copyOf(List.of(
                    account(firstId, 7, 0, sharedEmail, "1.0000"),
                    account(secondId, 7, 0, null, "2.0000"),
                    account(thirdId, 7, 0, sharedEmail, "3.0000"),
                    account(fourthId, 7, 0, null, "4.0000"),
                    account(fifthId, 7, 0, sharedEmail, "5.0000"))));
            return null;
        });
        insert(account(firstId, 8, 0, sharedEmail, "8.0000"), TENANT_8);
        insert(account(secondId, 8, 0, null, "8.0000"), TENANT_8);

        vev.read(TENANT_7, transaction -> {
            Rows<Account> firstPage = transaction.entities().many(
                    PgQueries.equal(AccountVev.EMAIL, sharedEmail, new QueryLimit(2)));
            assertEquals(List.of(firstId, thirdId),
                    firstPage.values().stream().map(Account::id).toList());
            assertTrue(firstPage.hasMore());

            Rows<Account> finalPage = transaction.entities().many(PgQueries.equalAfter(
                    AccountVev.EMAIL,
                    sharedEmail,
                    AccountVev.INSTANCE.key(thirdId),
                    new QueryLimit(2)));
            assertEquals(List.of(fifthId), finalPage.values().stream().map(Account::id).toList());
            assertFalse(finalPage.hasMore());

            Rows<Account> firstNullPage = transaction.entities().many(
                    PgQueries.isNull(AccountVev.EMAIL, new QueryLimit(1)));
            assertEquals(List.of(secondId),
                    firstNullPage.values().stream().map(Account::id).toList());
            assertTrue(firstNullPage.hasMore());

            Rows<Account> finalNullPage = transaction.entities().many(PgQueries.isNullAfter(
                    AccountVev.EMAIL,
                    AccountVev.INSTANCE.key(secondId),
                    new QueryLimit(2)));
            assertEquals(List.of(fourthId),
                    finalNullPage.values().stream().map(Account::id).toList());
            assertFalse(finalNullPage.hasMore());
            return null;
        });

        vev.read(TENANT_8, transaction -> {
            Rows<Account> tenantEquality = transaction.entities().many(
                    PgQueries.equal(AccountVev.EMAIL, sharedEmail, new QueryLimit(2)));
            assertEquals(List.of(firstId), tenantEquality.values().stream().map(Account::id).toList());
            assertFalse(tenantEquality.hasMore());

            Rows<Account> tenantNull = transaction.entities().many(
                    PgQueries.isNull(AccountVev.EMAIL, new QueryLimit(2)));
            assertEquals(List.of(secondId), tenantNull.values().stream().map(Account::id).toList());
            assertFalse(tenantNull.hasMore());
            return null;
        });
    }

    @Test
    void forgedNullableIndexIsRejectedBeforeSqlWithoutPoisoningTheTransaction() {
        Account existing = insert(account(
                id("forged-index-existing"), 7, 0, null, "1.0000"), TENANT_7);
        PgNullableIndex<IntegrationModelVev.Model, Account, UUID, String> forged = new PgNullableIndex<>(
                AccountVev.INSTANCE,
                AccountVev.EMAIL.indexName(),
                AccountVev.EMAIL.columnIndex(),
                String.class);
        BoundedQuery<IntegrationModelVev.Model, Account> forgedQuery =
                PgQueries.isNull(forged, new QueryLimit(1));

        vev.read(TENANT_7, transaction -> {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> transaction.entities().many(forgedQuery));
            assertEquals("Index token is not from this generated Vev model", failure.getMessage());
            assertEquals(existing, transaction.entities().find(AccountVev.INSTANCE.key(existing.id())).orElseThrow());
            return null;
        });
    }

    @Test
    void oversizedIndexedEqualityValueIsRejectedBeforeSqlWithoutPoisoningTheTransaction() {
        Account existing = insert(account(
                id("oversized-index-existing"), 7, 0, "existing@example.test", "1.0000"), TENANT_7);

        vev.read(TENANT_7, transaction -> {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> transaction.entities().many(PgQueries.equal(
                            AccountVev.EMAIL,
                            "x".repeat(256),
                            new QueryLimit(1))));
            assertEquals("email exceeds its generated character bound", failure.getMessage());
            assertEquals(existing, transaction.entities().find(AccountVev.INSTANCE.key(existing.id())).orElseThrow());
            return null;
        });
    }

    @Test
    void appendOnlyInstantEntityUsesDirectGeneratedCodec() {
        AuditEvent event = new AuditEvent(
                id("audit"),
                7,
                Instant.parse("2026-08-30T12:34:56.123456Z"),
                LocalDateTime.parse("2026-08-30T12:34:56.123456"),
                LocalDate.parse("2026-08-30"),
                "ACCOUNT_OPENED");

        AuditEvent inserted = vev.write(TENANT_7, transaction ->
                transaction.entities().insert(AuditEventVev.INSTANCE, event));
        AuditEvent loaded = vev.read(TENANT_7, transaction ->
                transaction.entities().find(AuditEventVev.INSTANCE.key(event.id())).orElseThrow());

        assertEquals(event, inserted);
        assertEquals(event, loaded);
    }

    @Test
    void setBasedBatchInsertRoundTripsEveryTemporalArrayCodecInInputOrder() {
        AuditEvent second = new AuditEvent(
                id("audit-batch-second"),
                7,
                Instant.parse("2026-08-30T12:34:57.654321Z"),
                LocalDateTime.parse("2026-08-30T14:34:57.654321"),
                LocalDate.parse("2026-08-31"),
                "SECOND");
        AuditEvent first = new AuditEvent(
                id("audit-batch-first"),
                7,
                Instant.parse("2026-08-30T12:34:56.123456Z"),
                LocalDateTime.parse("2026-08-30T14:34:56.123456"),
                LocalDate.parse("2026-08-30"),
                "FIRST");
        Batch<AuditEvent> expected = Batch.copyOf(List.of(second, first));

        Batch<AuditEvent> inserted = vev.write(TENANT_7, transaction ->
                transaction.entities().insertMultiple(AuditEventVev.INSTANCE, expected));
        Batch<EntityLookup<IntegrationModelVev.Model, AuditEvent, UUID>> loaded =
                vev.read(TENANT_7, transaction -> transaction.entities().findMultiple(
                        AuditEventVev.INSTANCE,
                        Batch.copyOf(List.of(second.id(), first.id()))));

        assertEquals(expected, inserted);
        assertEquals(second, ((EntityLookup.Found<?, AuditEvent, ?>) loaded.get(0)).entity());
        assertEquals(first, ((EntityLookup.Found<?, AuditEvent, ?>) loaded.get(1)).entity());
    }

    @Test
    void databaseTemporalInfinitiesAreRejectedDuringHydrationWithoutValueExposure() throws SQLException {
        UUID positiveInfinity = id("positive-infinity");
        UUID negativeInfinity = id("negative-infinity");
        database.insertInfiniteAuditEvent(positiveInfinity, 7, true);
        database.insertInfiniteAuditEvent(negativeInfinity, 7, false);

        for (UUID eventId : List.of(positiveInfinity, negativeInfinity)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                    vev.read(TENANT_7, transaction ->
                            transaction.entities().find(AuditEventVev.INSTANCE.key(eventId))));
            String diagnostics = causalMessages(failure);
            assertFalse(diagnostics.contains("infinity"));
            assertFalse(diagnostics.contains("999999999"));
            assertFalse(diagnostics.contains("294276"));
        }
    }

    @Test
    void caughtSqlFailureStillPoisonsAndRollsBackTheLexicalTransaction() {
        UUID accountId = id("duplicate");
        insert(account(accountId, 7, 0, "before@example.test", "2.00"), TENANT_7);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                vev.write(TENANT_7, transaction -> {
                    assertThrows(IllegalStateException.class, () -> transaction.entities().insert(
                            AccountVev.INSTANCE,
                            account(accountId, 7, 0, "duplicate@example.test", "3.00")));
                    assertThrows(IllegalStateException.class, () ->
                            transaction.entities().find(AccountVev.INSTANCE.key(accountId)));
                    return null;
                }));

        assertTrue(failure.getMessage().contains("poisoned"));
        assertEquals("before@example.test", find(accountId, TENANT_7).email());
    }

    @Test
    void jakartaEntityAgentProvidesDetachedReadsAndSafeAssignedValueInserts() {
        UUID accountId = id("agent");
        insert(account(accountId, 7, 0, "agent@example.test", "5.00"), TENANT_7);
        UUID missing = id("agent-missing");
        Account inserted = account(id("agent-insert"), 7, 0, "immutable@example.test", "1.00");
        Account batchFirst = account(id("agent-insert-batch-first"), 7, 0, "first@example.test", "2.00");
        Account batchSecond = account(id("agent-insert-batch-second"), 7, 0, "second@example.test", "3.00");

        VevEntityAgents.callInTransaction(vev, TENANT_7, agent -> {
            Account first = agent.get(Account.class, accountId);
            Account second = agent.get(Account.class, accountId);
            assertNotSame(first, second);
            List<Account> values = agent.findMultiple(Account.class, List.of(accountId, missing));
            assertEquals(first, values.get(0));
            assertNull(values.get(1));
            assertThrows(EntityNotFoundException.class, () ->
                    agent.getMultiple(Account.class, List.of(accountId, missing)));
            assertThrows(UnsupportedOperationException.class, () -> agent.createQuery("from Account"));
            agent.insert(inserted);
            agent.insertMultiple(List.of(batchFirst, batchSecond));
            assertEquals(inserted, agent.get(Account.class, inserted.id()));
            assertEquals(batchFirst, agent.get(Account.class, batchFirst.id()));
            assertEquals(batchSecond, agent.get(Account.class, batchSecond.id()));
            assertSame(values, agent.fetch(values));
            return null;
        });

        assertEquals(inserted, find(inserted.id(), TENANT_7));
        assertEquals(batchFirst, find(batchFirst.id(), TENANT_7));
        assertEquals(batchSecond, find(batchSecond.id(), TENANT_7));
    }

    @Test
    void jakartaEntityAgentPrevalidatesDetachedMutationsBeforeSql() {
        UUID accountId = id("agent-cross-tenant-delete");
        Account tenantSeven = insert(account(accountId, 7, 0, "seven@example.test", "7.00"), TENANT_7);
        Account tenantEight = insert(account(accountId, 8, 0, "eight@example.test", "8.00"), TENANT_8);
        Account validBeforeWrongTenant = account(
                id("agent-batch-valid-before-wrong-tenant"), 7, 0, "valid@example.test", "9.00");
        Account wrongTenant = account(
                id("agent-batch-wrong-tenant"), 8, 0, "wrong@example.test", "10.00");
        Account validBeforeDifferentType = account(
                id("agent-batch-valid-before-different-type"), 7, 0, "mixed@example.test", "11.00");
        AuditEvent differentType = new AuditEvent(
                id("agent-batch-different-type"),
                7,
                Instant.parse("2026-08-30T12:34:56.123456Z"),
                LocalDateTime.parse("2026-08-30T12:34:56.123456"),
                LocalDate.parse("2026-08-30"),
                "ACCOUNT_OPENED");
        Account validBeforeWrongModel = account(
                id("agent-batch-valid-before-wrong-model"), 7, 0, "model@example.test", "12.00");

        VevEntityAgents.callInTransaction(vev, TENANT_7, agent -> {
            assertThrows(IllegalArgumentException.class, () ->
                    agent.insertMultiple(List.of(validBeforeWrongTenant, wrongTenant)));
            assertNull(agent.find(Account.class, validBeforeWrongTenant.id()));
            assertThrows(IllegalArgumentException.class, () ->
                    agent.insertMultiple(List.of(validBeforeDifferentType, differentType)));
            assertNull(agent.find(Account.class, validBeforeDifferentType.id()));
            assertThrows(IllegalArgumentException.class, () ->
                    agent.insertMultiple(List.of(validBeforeWrongModel, new WrongModel())));
            assertNull(agent.find(Account.class, validBeforeWrongModel.id()));
            assertEquals(tenantSeven, agent.get(Account.class, accountId));
            return null;
        });

        assertEquals(tenantSeven, find(accountId, TENANT_7));
        assertEquals(tenantEight, find(accountId, TENANT_8));
        boolean wrongTenantPrefixInserted = vev.read(TENANT_7, transaction -> transaction.entities()
                .find(AccountVev.INSTANCE.key(validBeforeWrongTenant.id())).isPresent());
        boolean differentTypePrefixInserted = vev.read(TENANT_7, transaction -> transaction.entities()
                .find(AccountVev.INSTANCE.key(validBeforeDifferentType.id())).isPresent());
        boolean wrongModelPrefixInserted = vev.read(TENANT_7, transaction -> transaction.entities()
                .find(AccountVev.INSTANCE.key(validBeforeWrongModel.id())).isPresent());
        assertFalse(wrongTenantPrefixInserted);
        assertFalse(differentTypePrefixInserted);
        assertFalse(wrongModelPrefixInserted);
    }

    @Test
    void jakartaEntityAgentCaughtInvalidInsertMakesTheTransactionUncommittable() {
        Account valid = account(
                id("agent-before-invalid-insert"), 7, 0, "valid@example.test", "1.0000");
        Account invalid = new Account(
                id("agent-invalid-insert"),
                7,
                0L,
                "invalid@example.test",
                new BigDecimal("2.000"));

        PersistenceException failure = assertThrows(PersistenceException.class, () ->
                VevEntityAgents.callInTransaction(vev, TENANT_7, agent -> {
                    agent.insert(valid);
                    assertThrows(IllegalArgumentException.class, () -> agent.insert(invalid));
                    assertThrows(IllegalStateException.class, () -> agent.get(Account.class, valid.id()));
                    return null;
                }));

        assertEquals(
                "Vev EntityAgent transaction must roll back after an insert did not complete with a verified snapshot",
                failure.getMessage());
        boolean validInsertCommitted = vev.read(TENANT_7, transaction ->
                transaction.entities().find(AccountVev.INSTANCE.key(valid.id())).isPresent());
        assertFalse(validInsertCommitted);
    }

    @Test
    void jakartaEntityAgentRejectsEveryDeleteBeforeSql() {
        Account first = insert(account(id("agent-batch-first"), 7, 0, "first@example.test", "1.00"), TENANT_7);
        Account second = insert(account(id("agent-batch-second"), 7, 0, "second@example.test", "2.00"), TENANT_7);
        VevEntityAgents.callInTransaction(vev, TENANT_7, agent -> {
            assertThrows(UnsupportedOperationException.class, () -> agent.delete(first));
            assertThrows(UnsupportedOperationException.class, () -> agent.deleteMultiple(List.of(first, second)));
            assertEquals(first, agent.get(Account.class, first.id()));
            assertEquals(second, agent.get(Account.class, second.id()));
            return null;
        });

        assertEquals(first, find(first.id(), TENANT_7));
        assertEquals(second, find(second.id(), TENANT_7));
    }

    @Test
    void jakartaEntityAgentHandlesKnownAndVendorOptionsSafely() {
        Account account = insert(account(
                id("agent-options"), 7, 0, "options@example.test", "3.0000"), TENANT_7);

        VevEntityAgents.callInTransaction(vev, TENANT_7, agent -> {
            assertThrows(UnsupportedOperationException.class, () ->
                    agent.addOption(new FutureEntityAgentOption()));
            assertThrows(UnsupportedOperationException.class, () ->
                    agent.find(Account.class, account.id(), Timeout.ms(1)));
            assertThrows(UnsupportedOperationException.class, () ->
                    agent.setCacheRetrieveMode(CacheRetrieveMode.USE));
            assertEquals(account, agent.find(Account.class, account.id(), new FutureFindOption()));
            assertEquals(account, agent.find(Account.class, account.id(), (FindOption[]) null));
            assertEquals(account, agent.find(Account.class, account.id(), (FindOption) null));
            agent.setProperty("future.vendor.hint", new Object());
            assertTrue(agent.getProperties().isEmpty());
            assertThrows(NullPointerException.class, () -> agent.setProperty(null, new Object()));
            assertSame(agent, agent.unwrap(EntityAgent.class));
            assertThrows(PersistenceException.class, () -> agent.unwrap(String.class));
            assertEquals(account, agent.get(Account.class, account.id()));
            return null;
        });
    }

    @Test
    void jakartaEntityAgentIsThreadConfinedAndClosedAfterItsLexicalScope() {
        Account account = insert(account(
                id("agent-thread-confinement"), 7, 0, "thread@example.test", "4.0000"), TENANT_7);
        AtomicReference<EntityAgent> escaped = new AtomicReference<>();

        VevEntityAgents.callInTransaction(vev, TENANT_7, agent -> {
            escaped.set(agent);
            CompletableFuture<Throwable> foreignThreadOutcome = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    agent.isOpen();
                    foreignThreadOutcome.complete(new AssertionError("Foreign thread use unexpectedly succeeded"));
                } catch (Throwable failure) {
                    foreignThreadOutcome.complete(failure);
                }
            });
            IllegalStateException foreignThreadFailure = assertInstanceOf(
                    IllegalStateException.class,
                    foreignThreadOutcome.join());
            assertEquals(
                    "Vev EntityAgent belongs to a different thread",
                    foreignThreadFailure.getMessage());
            assertEquals(account, agent.get(Account.class, account.id()));
            return null;
        });

        EntityAgent closedAgent = escaped.get();
        assertFalse(closedAgent.isOpen());
        assertTrue(closedAgent.getProperties().isEmpty());
        CompletableFuture<Throwable> closedForeignThreadOutcome = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                closedAgent.getProperties();
                closedForeignThreadOutcome.complete(new AssertionError("Foreign thread use unexpectedly succeeded"));
            } catch (Throwable failure) {
                closedForeignThreadOutcome.complete(failure);
            }
        });
        IllegalStateException closedForeignThreadFailure = assertInstanceOf(
                IllegalStateException.class,
                closedForeignThreadOutcome.join());
        assertEquals(
                "Vev EntityAgent belongs to a different thread",
                closedForeignThreadFailure.getMessage());
        IllegalStateException closedFailure = assertThrows(
                IllegalStateException.class,
                closedAgent::getOptions);
        assertEquals("Vev EntityAgent is closed", closedFailure.getMessage());
    }

    @Test
    void bootstrapRejectsPrivilegedRolesFingerprintDriftAndMissingForceRls() throws SQLException {
        assertThrows(IllegalStateException.class, () ->
                runtime(database.adminDataSource()));

        database.setFingerprint(IntegrationModelVev.IDENTITY.name(), "wrong-fingerprint");
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setFingerprint(IntegrationModelVev.IDENTITY.name(), IntegrationModelVev.IDENTITY.fingerprint());
        }

        database.setForceRowSecurity(false);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setForceRowSecurity(true);
        }

        database.setAccountPolicySafe(false);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountPolicySafe(true);
        }

        database.setAuditColumnUpdatePrivilege(true);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAuditColumnUpdatePrivilege(false);
        }

        database.setSchemaCreatePrivilege(true);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setSchemaCreatePrivilege(false);
        }

        database.setFingerprintOperationalPrivileges(true);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setFingerprintOperationalPrivileges(false);
        }

        database.setIncomingFingerprintForeignKey(true);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setIncomingFingerprintForeignKey(false);
        }

        database.setNondeterministicEmailCollation(true);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setNondeterministicEmailCollation(false);
        }
    }

    @Test
    void bootstrapAcceptsTheExactGeneratedIndex() {
        assertDoesNotThrow(() -> runtime(database.applicationDataSource()));
    }

    @Test
    void bootstrapRejectsAMissingGeneratedIndex() throws SQLException {
        database.setAccountEmailIndexPresent(false);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountEmailIndexPresent(true);
        }
    }

    @Test
    void bootstrapRejectsAnExtraIndex() throws SQLException {
        database.setExtraAccountIndex(true);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setExtraAccountIndex(false);
        }
    }

    @Test
    void bootstrapRejectsAGeneratedIndexWithTheWrongShape() throws SQLException {
        database.setAccountEmailIndexWrongShape(true);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountEmailIndexWrongShape(false);
        }
    }

    @Test
    void bootstrapRejectsAUniqueGeneratedIndex() throws SQLException {
        try {
            database.setAccountEmailIndexUnique(true);
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountEmailIndexUnique(false);
        }
    }

    @Test
    void bootstrapRejectsAPartialGeneratedIndex() throws SQLException {
        try {
            database.setAccountEmailIndexPartial(true);
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountEmailIndexPartial(false);
        }
    }

    @Test
    void bootstrapRejectsAnExpressionGeneratedIndex() throws SQLException {
        try {
            database.setAccountEmailIndexExpression(true);
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountEmailIndexExpression(false);
        }
    }

    @Test
    void bootstrapRejectsAGeneratedIndexWithAnIncludedColumn() throws SQLException {
        try {
            database.setAccountEmailIndexIncludingBalance(true);
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountEmailIndexIncludingBalance(false);
        }
    }

    @Test
    void bootstrapRejectsADescendingGeneratedIndexKey() throws SQLException {
        try {
            database.setAccountEmailIndexDescending(true);
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountEmailIndexDescending(false);
        }
    }

    @Test
    void bootstrapRejectsANullsFirstGeneratedIndexKey() throws SQLException {
        try {
            database.setAccountEmailIndexNullsFirst(true);
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountEmailIndexNullsFirst(false);
        }
    }

    @Test
    void bootstrapRejectsAGeneratedIndexWithANondefaultCollation() throws SQLException {
        try {
            database.setAccountEmailIndexNondefaultCollation(true);
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountEmailIndexNondefaultCollation(false);
        }
    }

    @Test
    void bootstrapRejectsAGeneratedIndexWithReloptions() throws SQLException {
        try {
            database.setAccountEmailIndexReloptions(true);
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountEmailIndexReloptions(false);
        }
    }

    @Test
    void bootstrapRejectsUnsafeStorageConstraintsAndShadowedPolicyFunctions() throws SQLException {
        database.setAccountUnlogged(true);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountUnlogged(false);
        }

        database.setForeignKeyTouchingAccount(true);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setForeignKeyTouchingAccount(false);
        }

        database.setAccountInheritanceChild(true);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountInheritanceChild(false);
        }

        database.installShadowedPolicyFunction();
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.restoreTrustedPolicyFunction();
        }
    }

    @Test
    void checkoutRejectsAnUntrustedSearchPathBeforeParsingConfiguration() throws SQLException {
        database.installHostileBootstrapOperator();
        try {
            assertTrue(database.invokeHostileBootstrapOperatorProbe());
            assertEquals(1L, database.hostileBootstrapTripwireCount());
            database.resetHostileBootstrapTripwire();

            TenantAuthority<IntegrationModelVev.Model, Integer> authority =
                    IntegrationModelVev.newTenantAuthority();
            assertThrows(IllegalStateException.class, () -> new PgVev<>(
                    database.hostileSearchPathDataSource(),
                    IntegrationModelVev.POSTGRES,
                    authority));
            assertEquals(0L, database.hostileBootstrapTripwireCount());
        } finally {
            database.removeHostileBootstrapOperator();
        }
    }

    @Test
    void checkoutRejectsRetainedTempTypesBeforeParsingConfiguration() throws SQLException {
        database.installHostileTempDomainTripwire();
        Connection hostileConnection = database.openHostileTempDomainConnection();
        try {
            assertTrue(database.invokeHostileTempDomainProbe(hostileConnection));
            assertEquals(1L, database.hostileTempDomainTripwireCount());
            database.resetHostileTempDomainTripwire();

            TenantAuthority<IntegrationModelVev.Model, Integer> authority =
                    IntegrationModelVev.newTenantAuthority();
            PgVev<IntegrationModelVev.Model, Integer> hostilePoolRuntime = new PgVev<>(
                    firstCleanThenRetainedConnection(database.applicationDataSource(), hostileConnection),
                    IntegrationModelVev.POSTGRES,
                    authority);
            TenantScope<IntegrationModelVev.Model, Integer> tenant = authority.scope(7);
            AtomicInteger workInvocations = new AtomicInteger();

            assertThrows(IllegalStateException.class, () -> hostilePoolRuntime.write(tenant, transaction -> {
                workInvocations.incrementAndGet();
                return null;
            }));

            assertEquals(0, workInvocations.get());
            assertEquals(0L, database.hostileTempDomainTripwireCount());
        } finally {
            hostileConnection.close();
            database.removeHostileTempDomainTripwire();
        }
    }

    private static Account insert(Account account, TenantScope<IntegrationModelVev.Model, Integer> tenant) {
        return vev.write(tenant, transaction -> transaction.entities().insert(AccountVev.INSTANCE, account));
    }

    private static Account find(UUID accountId, TenantScope<IntegrationModelVev.Model, Integer> tenant) {
        return vev.read(tenant, transaction ->
                transaction.entities().find(AccountVev.INSTANCE.key(accountId)).orElseThrow());
    }

    private static Account account(UUID id, int tenant, long version, String email, String balance) {
        return new Account(id, tenant, version, email, new BigDecimal(balance).setScale(4));
    }

    private static PgVev<IntegrationModelVev.Model, Integer> runtime(DataSource dataSource) {
        return new PgVev<>(dataSource, IntegrationModelVev.POSTGRES, IntegrationModelVev.newTenantAuthority());
    }

    private static DataSource connectionForbiddenDataSource() {
        return (DataSource) Proxy.newProxyInstance(
                VevPostgresIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("Tenant authority rejection must precede DataSource access");
                });
    }

    private static DataSource connectionFailureDataSource() {
        return (DataSource) Proxy.newProxyInstance(
                VevPostgresIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new SQLException("synthetic connection failure");
                    }
                    throw new AssertionError("Unexpected DataSource operation: " + method.getName());
                });
    }

    private static DataSource firstCleanThenRetainedConnection(
            DataSource cleanDataSource,
            Connection retainedConnection) {
        AtomicInteger acquisitions = new AtomicInteger();
        return (DataSource) Proxy.newProxyInstance(
                VevPostgresIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("getConnection")
                            || arguments != null && arguments.length != 0) {
                        throw new AssertionError("Unexpected DataSource operation: " + method.getName());
                    }
                    if (acquisitions.getAndIncrement() == 0) {
                        return cleanDataSource.getConnection();
                    }
                    return nonClosing(retainedConnection);
                });
    }

    private static DataSource batchUpdateCountingDataSource(
            DataSource delegate,
            AtomicInteger batchUpdateStatements) {
        return (DataSource) Proxy.newProxyInstance(
                VevPostgresIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (method.getName().equals("getConnection")
                                && result instanceof Connection connection) {
                            return batchUpdateCountingConnection(connection, batchUpdateStatements);
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static Connection batchUpdateCountingConnection(
            Connection delegate,
            AtomicInteger batchUpdateStatements) {
        return (Connection) Proxy.newProxyInstance(
                VevPostgresIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("prepareStatement")
                            && arguments != null
                            && arguments.length > 0
                            && arguments[0] instanceof String sql
                            && sql.contains("AS (UPDATE \"vev_it\".\"account\" AS \"__vev_target\"")) {
                        batchUpdateStatements.incrementAndGet();
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static DataSource arrayCleanupFailureDataSource(
            DataSource delegate,
            AtomicInteger cleanupFailures) {
        return (DataSource) Proxy.newProxyInstance(
                VevPostgresIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (method.getName().equals("getConnection")
                                && result instanceof Connection connection) {
                            return arrayCleanupFailureConnection(connection, cleanupFailures);
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static Connection arrayCleanupFailureConnection(
            Connection delegate,
            AtomicInteger cleanupFailures) {
        return (Connection) Proxy.newProxyInstance(
                VevPostgresIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (method.getName().equals("createArrayOf") && result instanceof Array array) {
                            return arrayWithOneCleanupFailure(array, cleanupFailures);
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static Array arrayWithOneCleanupFailure(Array delegate, AtomicInteger cleanupFailures) {
        return (Array) Proxy.newProxyInstance(
                VevPostgresIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Array.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (method.getName().equals("free") && cleanupFailures.compareAndSet(0, 1)) {
                            throw new SQLException("synthetic JDBC array cleanup failure");
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static Connection nonClosing(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                VevPostgresIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    try {
                        return method.invoke(connection, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TenantAuthority<IntegrationModelVev.Model, Integer> eraseModelType(
            TenantAuthority<?, Integer> authority) {
        return (TenantAuthority) authority;
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String causalMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }

    private static Account foundAccount(EntityLookup<IntegrationModelVev.Model, Account, UUID> lookup) {
        if (!(lookup instanceof EntityLookup.Found<?, ?, ?> found)) {
            throw new AssertionError("Expected found Account lookup result");
        }
        return assertInstanceOf(Account.class, found.entity());
    }

    private static final class WrongModel {
        private WrongModel() {
        }
    }

    private static final class FutureEntityAgentOption implements EntityAgent.Option {
    }

    private static final class FutureFindOption implements FindOption {
    }

    private static final class HostileBigDecimal extends BigDecimal {
        private static final long serialVersionUID = 1L;

        private HostileBigDecimal(String value) {
            super(value);
        }

        @Override
        public boolean equals(Object value) {
            throw new AssertionError("Application equality must not run");
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }
}
