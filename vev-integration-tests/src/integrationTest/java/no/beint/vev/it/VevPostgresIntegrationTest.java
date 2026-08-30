package no.beint.vev.it;

import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.EntityAgent;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.Timeout;
import no.beint.vev.Batch;
import no.beint.vev.BoundedQuery;
import no.beint.vev.DeleteResult;
import no.beint.vev.EntityLookup;
import no.beint.vev.ModelIdentity;
import no.beint.vev.MutationEffect;
import no.beint.vev.MutationResult;
import no.beint.vev.QueryLimit;
import no.beint.vev.Rows;
import no.beint.vev.TenantAuthority;
import no.beint.vev.TenantScope;
import no.beint.vev.WriteEntities;
import no.beint.vev.jakarta.VevEntityAgents;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
            assertEquals(MutationEffect.UPDATED, update.effect());
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
    void nullableEmailRoundTripsThroughInsertFindUpdateAndUpsert() {
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

        Account upserted = vev.write(TENANT_7, transaction -> {
            Account changed = new Account(
                    updated.id(), updated.tenantId(), updated.version(), null, new BigDecimal("3.0000"));
            MutationResult<IntegrationModelVev.Model, Account, UUID, Long> result =
                    transaction.entities().upsert(AccountVev.INSTANCE, changed);
            MutationResult.Applied<?, ?, ?, ?> applied = assertInstanceOf(MutationResult.Applied.class, result);
            return assertInstanceOf(Account.class, applied.entity());
        });

        assertEquals(2L, upserted.version());
        assertNull(upserted.email());
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
    void atomicUpsertDistinguishesInsertAndUpdate() {
        UUID accountId = id("upsert");
        Account initial = account(accountId, 7, 0, "created@example.test", "4.00");

        Account inserted = vev.write(TENANT_7, transaction -> {
            MutationResult<IntegrationModelVev.Model, Account, UUID, Long> result =
                    transaction.entities().upsert(AccountVev.INSTANCE, initial);
            if (!(result instanceof MutationResult.Applied<?, ?, ?, ?> applied)) {
                throw new AssertionError("Expected applied upsert");
            }
            assertEquals(MutationEffect.INSERTED, applied.effect());
            return assertInstanceOf(Account.class, applied.entity());
        });

        vev.write(TENANT_7, transaction -> {
            Account changed = new Account(
                    inserted.id(), inserted.tenantId(), inserted.version(), "updated@example.test", inserted.balance());
            MutationResult<IntegrationModelVev.Model, Account, UUID, Long> result =
                    transaction.entities().upsert(AccountVev.INSTANCE, changed);
            if (!(result instanceof MutationResult.Applied<?, ?, ?, ?> applied)) {
                throw new AssertionError("Expected applied upsert");
            }
            assertEquals(MutationEffect.UPDATED, applied.effect());
            assertEquals(1L, applied.version());
            return null;
        });

        vev.write(TENANT_7, transaction -> {
            MutationResult<IntegrationModelVev.Model, Account, UUID, Long> stale = transaction.entities().upsert(
                    AccountVev.INSTANCE,
                    new Account(accountId, 7, 0L, "stale@example.test", inserted.balance()));
            assertInstanceOf(MutationResult.Conflict.class, stale);

            UUID missingId = id("upsert-missing-nonzero-version");
            MutationResult<IntegrationModelVev.Model, Account, UUID, Long> missing = transaction.entities().upsert(
                    AccountVev.INSTANCE,
                    account(missingId, 7, 4, "must-not-resurrect@example.test", "4.00"));
            assertInstanceOf(MutationResult.Missing.class, missing);
            assertTrue(transaction.entities().find(AccountVev.INSTANCE.key(missingId)).isEmpty());
            return null;
        });
    }

    @Test
    void concurrentInvisibleInsertAbortsTheSerializableTransaction() throws Exception {
        UUID accountId = id("concurrent-upsert");
        Account winner = account(accountId, 7, 0, "winner@example.test", "7.0000");
        Account contender = account(accountId, 7, 0, "contender@example.test", "8.0000");

        try (Connection winningTransaction = database.openApplicationTransaction(7);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            database.insertUncommittedAccount(winningTransaction, winner);
            Future<MutationResult<IntegrationModelVev.Model, Account, UUID, Long>> future = executor.submit(() ->
                    vev.write(TENANT_7, transaction ->
                            transaction.entities().upsert(AccountVev.INSTANCE, contender)));
            database.awaitBlockedUpsert();
            winningTransaction.commit();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            IllegalStateException serializationFailure =
                    assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("PostgreSQL operation failed [SQLSTATE 40001]", serializationFailure.getMessage());
        }

        assertEquals(winner, find(accountId, TENANT_7));
    }

    @Test
    void versionedDeleteDistinguishesConflictAndSuccess() {
        UUID accountId = id("delete");
        Account inserted = insert(account(accountId, 7, 0, "delete@example.test", "9.00"), TENANT_7);

        vev.write(TENANT_7, transaction -> {
            DeleteResult<IntegrationModelVev.Model, Account, UUID, Long> conflict = transaction.entities().delete(
                    AccountVev.INSTANCE.versionedKey(accountId, inserted.version() + 1));
            assertInstanceOf(DeleteResult.Conflict.class, conflict);

            DeleteResult<IntegrationModelVev.Model, Account, UUID, Long> deleted = transaction.entities().delete(
                    AccountVev.INSTANCE.versionedKey(accountId, inserted.version()));
            assertInstanceOf(DeleteResult.Deleted.class, deleted);
            return null;
        });

        assertFalse(vev.read(TENANT_7, transaction -> transaction.entities().find(AccountVev.INSTANCE.key(accountId))).isPresent());
    }

    @Test
    void batchDeleteValidatesEveryVersionBeforeIssuingSql() {
        Account first = insert(account(id("batch-delete-first"), 7, 0, "first@example.test", "1.00"), TENANT_7);
        Account second = insert(account(id("batch-delete-second"), 7, 0, "second@example.test", "2.00"), TENANT_7);

        vev.write(TENANT_7, transaction -> {
            assertThrows(IllegalArgumentException.class, () -> transaction.entities().deleteMultiple(Batch.copyOf(List.of(
                    AccountVev.INSTANCE.versionedKey(first.id(), first.version()),
                    AccountVev.INSTANCE.versionedKey(second.id(), -1L))
            )));
            assertTrue(transaction.entities().find(AccountVev.INSTANCE.key(first.id())).isPresent());
            assertTrue(transaction.entities().find(AccountVev.INSTANCE.key(second.id())).isPresent());
            return null;
        });

        assertEquals(first, find(first.id(), TENANT_7));
        assertEquals(second, find(second.id(), TENANT_7));
    }

    @Test
    void boundedScanUsesTheGeneratedPlanAndRejectsForgedQueries() {
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID thirdId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        insert(account(firstId, 7, 0, "first@example.test", "1.00"), TENANT_7);
        insert(account(secondId, 7, 0, "second@example.test", "2.00"), TENANT_7);
        insert(account(thirdId, 7, 0, "third@example.test", "3.00"), TENANT_7);

        vev.read(TENANT_7, transaction -> {
            Rows<Account> rows = transaction.entities().many(
                    PgQueries.scanById(AccountVev.INSTANCE, new QueryLimit(2)));
            assertEquals(List.of(firstId, secondId), rows.values().stream().map(Account::id).toList());
            assertTrue(rows.hasMore());

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
    void jakartaEntityAgentProvidesDetachedReadsAndRejectsUnsafeSurface() {
        UUID accountId = id("agent");
        insert(account(accountId, 7, 0, "agent@example.test", "5.00"), TENANT_7);
        UUID missing = id("agent-missing");

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
            assertThrows(UnsupportedOperationException.class, () -> agent.insert(
                    account(id("agent-insert"), 7, 0, "immutable@example.test", "1.00")));
            assertSame(values, agent.fetch(values));
            return null;
        });

        assertFalse(vev.read(TENANT_7, transaction ->
                transaction.entities().find(AccountVev.INSTANCE.key(id("agent-insert")))).isPresent());
    }

    @Test
    void jakartaEntityAgentRejectsCrossTenantDetachedDeletesBeforeSql() {
        UUID accountId = id("agent-cross-tenant-delete");
        Account tenantSeven = insert(account(accountId, 7, 0, "seven@example.test", "7.00"), TENANT_7);
        Account tenantEight = insert(account(accountId, 8, 0, "eight@example.test", "8.00"), TENANT_8);

        VevEntityAgents.callInTransaction(vev, TENANT_7, agent -> {
            assertThrows(IllegalArgumentException.class, () -> agent.delete(tenantEight));
            assertEquals(tenantSeven, agent.get(Account.class, accountId));
            return null;
        });

        assertEquals(tenantSeven, find(accountId, TENANT_7));
        assertEquals(tenantEight, find(accountId, TENANT_8));
    }

    @Test
    void jakartaEntityAgentRejectsBatchDeleteBeforeSql() {
        Account first = insert(account(id("agent-batch-first"), 7, 0, "first@example.test", "1.00"), TENANT_7);
        Account second = insert(account(id("agent-batch-second"), 7, 0, "second@example.test", "2.00"), TENANT_7);
        VevEntityAgents.callInTransaction(vev, TENANT_7, agent -> {
            assertThrows(UnsupportedOperationException.class, () -> agent.deleteMultiple(List.of(first, second)));
            assertEquals(first, agent.get(Account.class, first.id()));
            assertEquals(second, agent.get(Account.class, second.id()));
            return null;
        });

        assertEquals(first, find(first.id(), TENANT_7));
        assertEquals(second, find(second.id(), TENANT_7));
    }

    @Test
    void jakartaEntityAgentCaughtOptimisticFailureRollsBackEveryEarlierEffect() {
        Account first = insert(account(
                id("agent-rollback-first"), 7, 0, "first@example.test", "1.0000"), TENANT_7);
        Account second = insert(account(
                id("agent-rollback-second"), 7, 0, "second@example.test", "2.0000"), TENANT_7);
        Account staleSecond = new Account(
                second.id(), second.tenantId(), second.version() + 1, second.email(), second.balance());

        OptimisticLockException failure = assertThrows(OptimisticLockException.class, () ->
                VevEntityAgents.callInTransaction(vev, TENANT_7, agent -> {
                    agent.delete(first);
                    assertThrows(OptimisticLockException.class, () -> agent.delete(staleSecond));
                    assertThrows(IllegalStateException.class, () -> agent.get(Account.class, first.id()));
                    return null;
                }));

        assertEquals("Optimistic delete failed for " + Account.class.getName(), failure.getMessage());
        assertEquals(first, find(first.id(), TENANT_7));
        assertEquals(second, find(second.id(), TENANT_7));
    }

    @Test
    void jakartaEntityAgentFailsClosedForUnknownOptionsAndTimeouts() {
        Account account = insert(account(
                id("agent-options"), 7, 0, "options@example.test", "3.0000"), TENANT_7);

        VevEntityAgents.callInTransaction(vev, TENANT_7, agent -> {
            assertThrows(UnsupportedOperationException.class, () ->
                    agent.addOption(new FutureEntityAgentOption()));
            assertThrows(UnsupportedOperationException.class, () ->
                    agent.find(Account.class, account.id(), Timeout.ms(1)));
            assertThrows(UnsupportedOperationException.class, () ->
                    agent.setCacheRetrieveMode(CacheRetrieveMode.USE));
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
    void bootstrapRejectsUnsafeStorageConstraintsAndShadowedPolicyFunctions() throws SQLException {
        database.setAccountUnlogged(true);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setAccountUnlogged(false);
        }

        database.setSecondaryIndex(true);
        try {
            assertThrows(IllegalStateException.class, () ->
                    runtime(database.applicationDataSource()));
        } finally {
            database.setSecondaryIndex(false);
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
    void checkoutInstallsTrustedSearchPathBeforeParsingConfiguration() throws SQLException {
        database.installHostileBootstrapOperator();
        try {
            assertTrue(database.invokeHostileBootstrapOperatorProbe());
            assertEquals(1L, database.hostileBootstrapTripwireCount());
            database.resetHostileBootstrapTripwire();

            TenantAuthority<IntegrationModelVev.Model, Integer> authority =
                    IntegrationModelVev.newTenantAuthority();
            PgVev<IntegrationModelVev.Model, Integer> hostilePoolRuntime = new PgVev<>(
                    database.hostileSearchPathDataSource(),
                    IntegrationModelVev.POSTGRES,
                    authority);
            TenantScope<IntegrationModelVev.Model, Integer> tenant = authority.scope(7);
            AtomicInteger workInvocations = new AtomicInteger();

            hostilePoolRuntime.write(tenant, transaction -> {
                workInvocations.incrementAndGet();
                return null;
            });

            assertEquals(1, workInvocations.get());
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
}
