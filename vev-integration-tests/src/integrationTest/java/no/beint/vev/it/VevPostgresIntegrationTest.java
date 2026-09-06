package no.beint.vev.it;

import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.EntityAgent;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.FindOption;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Timeout;
import no.beint.vev.Batch;
import no.beint.vev.Binary;
import no.beint.vev.BoundedQuery;
import no.beint.vev.EntityLookup;
import no.beint.vev.ModelIdentity;
import no.beint.vev.MutationResult;
import no.beint.vev.DeleteTarget;
import no.beint.vev.DeleteResult;
import no.beint.vev.QueryLimit;
import no.beint.vev.Rows;
import no.beint.vev.TenantAuthority;
import no.beint.vev.TenantScope;
import no.beint.vev.WriteEntities;
import no.beint.vev.fixtures.KotlinEntry;
import no.beint.vev.fixtures.KotlinEntryVev;
import no.beint.vev.fixtures.KotlinIdentity;
import no.beint.vev.fixtures.KotlinIdentityVev;
import no.beint.vev.fixtures.KotlinBinary;
import no.beint.vev.fixtures.KotlinBinaryVev;
import no.beint.vev.fixtures.KotlinTextVev;
import no.beint.vev.fixtures.KotlinClock;
import no.beint.vev.fixtures.KotlinClockVev;
import no.beint.vev.fixtures.KotlinReadOnlyVev;
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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void sharedReferenceReadsPreserveEveryBoundedQueryShapeInBothProtocolsAndTenants() throws SQLException {
        database.seedSharedRows();
        UUID selection = id("shared-selection");
        vev.write(TENANT_7, tx -> tx.entities().insert(CatalogSelectionVev.INSTANCE, new CatalogSelection(selection, 7, 1)));
        vev.write(TENANT_8, tx -> tx.entities().insert(CatalogSelectionVev.INSTANCE, new CatalogSelection(selection, 8, 2)));
        for (boolean binary : List.of(false, true)) {
            var authority = IntegrationModelVev.newTenantAuthority();
            var count = new AtomicInteger();
            var runtime = new PgVev<>(entityStatementCountingDataSource(database.applicationDataSource(binary), count), IntegrationModelVev.POSTGRES, authority);
            for (int tenantId : List.of(7, 8)) {
                runtime.read(authority.scope(tenantId), tx -> {
                    int before = count.get();
                    assertTrue(tx.entities().findMultiple(SharedCatalogVev.INSTANCE, Batch.empty()).isEmpty());
                    assertThrows(IllegalArgumentException.class, () -> tx.entities().findMultiple(SharedCatalogVev.INSTANCE, Batch.copyOf(java.util.Collections.nCopies(9, 1))));
                    assertThrows(IllegalArgumentException.class, () -> tx.entities().many(PgQueries.scanById(SharedCatalogVev.INSTANCE, new QueryLimit(9))));
                    assertEquals(before, count.get());
                    var first = tx.entities().find(SharedCatalogVev.INSTANCE.key(1)).orElseThrow();
                    assertEquals("root", first.code());
                    assertNull(first.parentId());
                    var found = tx.entities().findMultiple(SharedCatalogVev.INSTANCE, Batch.copyOf(List.of(2, 99, 1, 2)));
                    assertEquals(before + 2, count.get());
                    assertInstanceOf(EntityLookup.Missing.class, found.get(1));
                    var second = ((EntityLookup.Found<?, SharedCatalog, ?>) found.get(0)).entity();
                    assertEquals(Long.MAX_VALUE, second.version());
                    assertEquals(1, second.parentId());
                    assertEquals(second, ((EntityLookup.Found<?, SharedCatalog, ?>) found.get(3)).entity());
                    var page = tx.entities().many(PgQueries.scanById(SharedCatalogVev.INSTANCE, new QueryLimit(1)));
                    assertEquals(List.of(first), page.values());
                    assertTrue(page.hasMore());
                    assertEquals(List.of(second), tx.entities().many(PgQueries.scanByIdAfter(SharedCatalogVev.INSTANCE.key(1), new QueryLimit(1))).values());
                    assertEquals(List.of(first), tx.entities().many(PgQueries.equal(SharedCatalogVev.LABEL, "group", new QueryLimit(1))).values());
                    assertEquals(List.of(3), tx.entities().many(PgQueries.equalAfter(SharedCatalogVev.LABEL, "group", SharedCatalogVev.INSTANCE.key(1), new QueryLimit(1))).values().stream().map(SharedCatalog::id).toList());
                    assertEquals(List.of(second), tx.entities().many(PgQueries.isNull(SharedCatalogVev.LABEL, new QueryLimit(1))).values());
                    assertEquals(List.of(4), tx.entities().many(PgQueries.isNullAfter(SharedCatalogVev.LABEL, SharedCatalogVev.INSTANCE.key(2), new QueryLimit(1))).values().stream().map(SharedCatalog::id).toList());
                    assertEquals("common", tx.entities().find(no.beint.vev.fixtures.KotlinSharedVev.INSTANCE.key(1)).orElseThrow().label());
                    assertEquals(1, tx.entities().many(PgQueries.equal(no.beint.vev.fixtures.KotlinSharedVev.ID, 1, new QueryLimit(1))).values().size());
                    assertTrue(tx.entities().many(PgQueries.equalAfter(no.beint.vev.fixtures.KotlinSharedVev.ID, 1, no.beint.vev.fixtures.KotlinSharedVev.INSTANCE.key(1), new QueryLimit(1))).values().isEmpty());
                    assertEquals(tenantId == 7 ? 1 : 2, tx.entities().find(CatalogSelectionVev.INSTANCE.key(selection)).orElseThrow().catalogId());
                    assertEquals(4, tx.entities().many(PgQueries.scanById(SharedCatalogVev.INSTANCE, new QueryLimit(8))).values().size());
                    assertTrue(tx.entities().find(SharedCatalogVev.INSTANCE.key(99)).isEmpty());
                    return null;
                });
            }
        }
    }

    @Test
    void sharedMappingsRemainReadOnlyThroughTheJakartaFacadeInsideWriteTransactions() throws SQLException {
        database.seedSharedRows();
        VevEntityAgents.runInTransaction(vev, TENANT_8, agent -> {
            var stored = agent.find(SharedCatalog.class, 1);
            assertNotNull(stored);
            assertEquals("root", stored.code());
            assertThrows(UnsupportedOperationException.class, () -> agent.insert(stored));
            assertThrows(UnsupportedOperationException.class, () -> agent.insertMultiple(List.of(stored)));
            assertThrows(IllegalArgumentException.class, () -> agent.update(stored));
            assertEquals(4, agent.find(SharedCatalog.class, 4).id());
        });
    }

    @Test
    void sharedReferenceAttestationRejectsPoliciesPrivilegesAndUnexpectedConstraintShapes() throws SQLException {
        try {
            for (String variant : List.of("noSelect", "insert", "update", "delete", "sequence", "enabledRls", "forcedRls",
                    "dormantPolicy", "indexOrder", "missingUnique", "wrongUnique", "missingReference", "wrongReference",
                    "cascade", "deferred", "disabledTrigger", "unmappedIncoming", "extraTenant", "matchFull", "unvalidated",
                    "wrongPrimaryKey", "insertTable", "selectGrantOption")) {
                database.sharedVariant(variant);
                assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()), variant);
            }
        } finally {
            database.sharedVariant("valid");
        }
        assertDoesNotThrow(() -> runtime(database.applicationDataSource()));
    }

    @Test
    void tenantWritesCanReferenceSharedRowsWithoutGivingSharedRowsMutationCapabilities() throws SQLException {
        database.seedSharedRows();
        for (var tenant : List.of(TENANT_7, TENANT_8)) {
            int tenantId = tenant == TENANT_7 ? 7 : 8;
            UUID key = id("shared-foreign-key");
            vev.write(tenant, tx -> {
                var inserted = tx.entities().insertMultiple(CatalogSelectionVev.INSTANCE, Batch.copyOf(List.of(
                        new CatalogSelection(key, tenantId, 2), new CatalogSelection(id("shared-null-fk"), tenantId, null))));
                assertEquals(2, inserted.size());
                assertEquals(1, tx.entities().find(SharedCatalogVev.INSTANCE.key(2)).orElseThrow().parentId());
                return null;
            });
            UUID earlier = id("shared-fk-earlier");
            assertThrows(IllegalStateException.class, () -> vev.write(tenant, tx -> {
                tx.entities().insert(CatalogSelectionVev.INSTANCE, new CatalogSelection(earlier, tenantId, 1));
                assertThrows(IllegalStateException.class, () -> tx.entities().insert(CatalogSelectionVev.INSTANCE,
                        new CatalogSelection(id("shared-fk-missing"), tenantId, 99)));
                return null;
            }));
            assertTrue(vev.read(tenant, tx -> tx.entities().find(CatalogSelectionVev.INSTANCE.key(earlier))).isEmpty());
        }
    }

    @Test
    void invalidSharedSnapshotPoisonsTheWholeTransactionEvenWhenItsReadFailureIsCaught() throws SQLException {
        database.seedSharedRows();
        database.negativeSharedVersion();
        UUID earlier = id("shared-invalid-earlier");
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> {
            tx.entities().insert(CatalogSelectionVev.INSTANCE, new CatalogSelection(earlier, 7, 2));
            assertThrows(IllegalStateException.class, () -> tx.entities().find(SharedCatalogVev.INSTANCE.key(1)));
            assertThrows(IllegalStateException.class, () -> tx.entities().find(SharedCatalogVev.INSTANCE.key(2)));
            return null;
        }));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(CatalogSelectionVev.INSTANCE.key(earlier))).isEmpty());
    }

    @Test
    void readOnlyMappingsPreserveStoredVersionsTenantBoundsAndEveryReadShape() throws SQLException {
        UUID key = id("read-only");
        database.seedReadOnlyRows(key);
        for (boolean binary : List.of(false, true)) {
            var authority = IntegrationModelVev.newTenantAuthority();
            var runtime = new PgVev<>(database.applicationDataSource(binary), IntegrationModelVev.POSTGRES, authority);
            runtime.read(authority.scope(7), tx -> {
                assertEquals("visible", tx.entities().find(ReadOnlySnapshotVev.INSTANCE.key(key)).orElseThrow().label());
                assertEquals("visible", tx.entities().find(KotlinReadOnlyVev.INSTANCE.key(1)).orElseThrow().label());
                var batch = tx.entities().findMultiple(ReadOnlyIdentityVev.INSTANCE, Batch.copyOf(List.of((short) 2, (short) 99, (short) 1)));
                assertInstanceOf(EntityLookup.Missing.class, batch.get(1));
                var first = ((EntityLookup.Found<?, ReadOnlyIdentity, ?>) batch.get(2)).entity();
                var second = ((EntityLookup.Found<?, ReadOnlyIdentity, ?>) batch.get(0)).entity();
                assertEquals(0L, first.version());
                assertEquals(Long.MAX_VALUE, second.version());
                var page = tx.entities().many(PgQueries.scanById(ReadOnlyIdentityVev.INSTANCE, new QueryLimit(1)));
                assertEquals(List.of(first), page.values());
                assertTrue(page.hasMore());
                assertEquals(List.of(second), tx.entities().many(PgQueries.scanByIdAfter(ReadOnlyIdentityVev.INSTANCE.key((short) 1), new QueryLimit(1))).values());
                assertEquals(List.of(first), tx.entities().many(PgQueries.equal(ReadOnlyIdentityVev.LABEL, "first", new QueryLimit(1))).values());
                assertTrue(tx.entities().many(PgQueries.equalAfter(ReadOnlyIdentityVev.LABEL, "first", ReadOnlyIdentityVev.INSTANCE.key((short) 1), new QueryLimit(1))).values().isEmpty());
                assertEquals(List.of(second), tx.entities().many(PgQueries.isNull(ReadOnlyIdentityVev.LABEL, new QueryLimit(1))).values());
                assertTrue(tx.entities().many(PgQueries.isNullAfter(ReadOnlyIdentityVev.LABEL, ReadOnlyIdentityVev.INSTANCE.key((short) 2), new QueryLimit(1))).values().isEmpty());
                return null;
            });
            runtime.write(authority.scope(8), tx -> {
                assertEquals("foreign", tx.entities().find(ReadOnlySnapshotVev.INSTANCE.key(key)).orElseThrow().label());
                assertEquals("foreign", tx.entities().find(ReadOnlyIdentityVev.INSTANCE.key((short) 1)).orElseThrow().label());
                assertTrue(tx.entities().find(ReadOnlyIdentityVev.INSTANCE.key((short) 2)).isEmpty());
                return null;
            });
        }
    }

    @Test
    void jakartaFacadeReadsReadOnlyMappingsAndRejectsTheirWritesBeforeSql() throws SQLException {
        UUID key = id("read-only-facade");
        database.seedReadOnlyRows(key);
        VevEntityAgents.runInTransaction(vev, TENANT_7, agent -> {
            var stored = agent.find(ReadOnlyIdentity.class, (short) 1);
            assertNotNull(stored);
            assertEquals(0L, stored.version());
            assertThrows(UnsupportedOperationException.class, () -> agent.insert(stored));
            assertThrows(UnsupportedOperationException.class, () -> agent.insertMultiple(List.of(stored)));
            assertThrows(IllegalArgumentException.class, () -> agent.update(stored));
            assertEquals("visible", agent.find(ReadOnlySnapshot.class, key).label());
        });
        assertEquals(0L, vev.read(TENANT_7, tx -> tx.entities().find(ReadOnlyIdentityVev.INSTANCE.key((short) 1))).orElseThrow().version());
    }

    @Test
    void readOnlyTablesRejectEveryWritePrivilegeAndKeepIdentitySequenceAccessAbsent() throws SQLException {
        try {
            for (String variant : List.of("noSelect", "insert", "update", "delete", "usage", "sequenceUpdate")) {
                database.readOnlyVariant(variant);
                assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()), variant);
            }
        } finally {
            database.readOnlyVariant("valid");
        }
        assertDoesNotThrow(() -> runtime(database.applicationDataSource()));
    }

    @Test
    void invalidStoredReadOnlyVersionRollsBackEarlierWritesEvenWhenCaught() throws SQLException {
        database.seedReadOnlyRows(id("read-only-version"));
        database.readOnlyVariant("negativeVersion");
        UUID earlier = id("read-only-earlier");
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> {
            tx.entities().insert(AccountVev.INSTANCE, account(earlier, 7, 0, "readonly@example.test", "1.0000"));
            assertThrows(IllegalStateException.class, () -> tx.entities().find(ReadOnlyIdentityVev.INSTANCE.key((short) 1)));
            assertThrows(IllegalStateException.class, () -> tx.entities().find(AccountVev.INSTANCE.key(earlier)));
            return null;
        }));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlier))).isEmpty());
    }

    @Test
    void corruptDeleteResultsAndCleanupFailuresPoisonEvenWhenTheCallerCatchesThem() {
        for (boolean batch : List.of(false, true)) {
            var corruptions = new java.util.ArrayList<>(List.of("key", "tenant", "version", "null", "extra",
                    "readerError", "driverFailure", "resultClose", "statementClose"));
            corruptions.addAll(batch ? List.of("ordinal", "missing", "arrayLength") : List.of("outcome"));
            for (String corruption : corruptions) {
                var rows = vev.write(TENANT_7, tx -> tx.entities().createMultiple(IdentityCounterVev.INSTANCE,
                        Batch.copyOf(java.util.Collections.nCopies(2, new IdentityCounterVev.New()))));
                var targets = Batch.copyOf(rows.values().stream()
                        .map(row -> new DeleteTarget<>(IdentityCounterVev.INSTANCE, row.id(), row.version())).toList());
                var authority = IntegrationModelVev.newTenantAuthority();
                var count = new AtomicInteger();
                var runtime = new PgVev<>(DeleteObservationDataSource.observe(database.applicationDataSource(), count, corruption),
                        IntegrationModelVev.POSTGRES, authority);
                UUID earlier = id("delete-" + batch + '-' + corruption);
                assertThrows(IllegalStateException.class, () -> runtime.write(authority.scope(7), tx -> {
                    tx.entities().insert(AccountVev.INSTANCE, account(earlier, 7, 0, batch + corruption + "@example.test", "1.0000"));
                    org.junit.jupiter.api.function.Executable operation = () -> {
                        if (batch) tx.entities().deleteMultiple(IdentityCounterVev.INSTANCE, targets);
                        else tx.entities().delete(targets.get(0));
                    };
                    if (corruption.equals("readerError")) assertThrows(AssertionError.class, operation);
                    else assertThrows(IllegalStateException.class, operation);
                    assertThrows(IllegalStateException.class, () -> tx.entities().find(IdentityCounterVev.INSTANCE.key(rows.get(0).id())));
                    return null;
                }), batch + corruption);
                assertEquals(1, count.get());
                assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlier))).isEmpty());
                for (var row : rows) assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(IdentityCounterVev.INSTANCE.key(row.id()))).isPresent());
            }
        }
    }

    @Test
    void deletionCannotEscapeWriteOwnershipOrUseAForeignMappingToken() {
        var row = vev.write(TENANT_7, tx -> tx.entities().create(IdentityCounterVev.INSTANCE, new IdentityCounterVev.New()));
        var target = new DeleteTarget<>(IdentityCounterVev.INSTANCE, row.id(), row.version());
        var authority = IntegrationModelVev.newTenantAuthority();
        var count = new AtomicInteger();
        var runtime = new PgVev<>(DeleteObservationDataSource.observe(database.applicationDataSource(), count, ""),
                IntegrationModelVev.POSTGRES, authority);
        runtime.read(authority.scope(7), tx -> {
            var write = (WriteEntities<IntegrationModelVev.Model>) tx.entities();
            assertThrows(IllegalStateException.class, () -> write.delete(target));
            assertThrows(IllegalStateException.class, () -> write.deleteMultiple(IdentityCounterVev.INSTANCE, Batch.one(target)));
            return null;
        });
        var escaped = new AtomicReference<WriteEntities<IntegrationModelVev.Model>>();
        runtime.write(authority.scope(7), tx -> {
            escaped.set(tx.entities());
            @SuppressWarnings("unchecked")
            var foreignType = (no.beint.vev.DeletableEntityType<IntegrationModelVev.Model, IdentityCounter, Integer, Integer>)
                    Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{no.beint.vev.DeletableEntityType.class},
                            (proxy, method, arguments) -> method.invoke(IdentityCounterVev.INSTANCE, arguments));
            var foreign = new DeleteTarget<>(foreignType, row.id(), row.version());
            assertThrows(IllegalArgumentException.class, () -> tx.entities().delete(foreign));
            assertThrows(IllegalArgumentException.class, () -> tx.entities().deleteMultiple(IdentityCounterVev.INSTANCE,
                    Batch.copyOf(List.of(target, foreign))));
            assertThrows(NullPointerException.class, () -> tx.entities().delete(null));
            assertThrows(NullPointerException.class, () -> tx.entities().deleteMultiple(IdentityCounterVev.INSTANCE, null));
            return null;
        });
        assertThrows(IllegalStateException.class, () -> escaped.get().delete(target));
        assertThrows(IllegalStateException.class, () -> escaped.get().deleteMultiple(IdentityCounterVev.INSTANCE, Batch.one(target)));
        assertEquals(0, count.get());
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(IdentityCounterVev.INSTANCE.key(row.id()))).isPresent());
    }

    @Test
    void concurrentDeleteAndUpdateCannotBothApplyToTheSameVersion() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int attempt = 0; attempt < 8; attempt++) {
                var row = vev.write(TENANT_7, tx -> tx.entities().create(IdentityCounterVev.INSTANCE, new IdentityCounterVev.New()));
                var ready = new java.util.concurrent.CountDownLatch(2);
                var release = new java.util.concurrent.CountDownLatch(1);
                var futures = new java.util.ArrayList<java.util.concurrent.Future<Object>>();
                for (boolean delete : List.of(false, true)) {
                    futures.add(executor.submit(() -> {
                        try {
                            return vev.write(TENANT_7, tx -> {
                                var snapshot = tx.entities().find(IdentityCounterVev.INSTANCE.key(row.id())).orElseThrow();
                                ready.countDown();
                                try {
                                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent fixture did not release");
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(interrupted);
                                }
                                return delete ? tx.entities().delete(new DeleteTarget<>(IdentityCounterVev.INSTANCE, row.id(), snapshot.version()))
                                        : tx.entities().update(IdentityCounterVev.INSTANCE, snapshot);
                            });
                        } catch (IllegalStateException failure) {
                            return failure;
                        }
                    }));
                }
                try {
                    assertTrue(ready.await(10, TimeUnit.SECONDS));
                } finally {
                    release.countDown();
                }
                var first = futures.get(0).get(15, TimeUnit.SECONDS);
                var second = futures.get(1).get(15, TimeUnit.SECONDS);
                assertTrue(first instanceof MutationResult.Applied<?, ?, ?, ?> && second instanceof IllegalStateException
                        || first instanceof IllegalStateException && second instanceof DeleteResult.Deleted<?, ?, ?, ?>);
                var rejected = (IllegalStateException) (first instanceof IllegalStateException ? first : second);
                assertTrue(rejected.getMessage().contains("SQLSTATE 40001"), rejected.getMessage());
                var remaining = vev.read(TENANT_7, tx -> tx.entities().find(IdentityCounterVev.INSTANCE.key(row.id())));
                if (first instanceof MutationResult.Applied<?, ?, ?, ?>) assertEquals(1, remaining.orElseThrow().version());
                else assertTrue(remaining.isEmpty());
            }
        }
    }

    @Test
    void explicitDeletionClassifiesOneSnapshotAndNeverReusesTheGeneratedIdentifier() throws SQLException {
        var row = vev.write(TENANT_7, tx -> tx.entities().create(IdentityCounterVev.INSTANCE, new IdentityCounterVev.New()));
        var target = new DeleteTarget<>(IdentityCounterVev.INSTANCE, row.id(), row.version());
        assertInstanceOf(DeleteResult.Missing.class, vev.write(TENANT_8, tx -> tx.entities().delete(target)));
        assertInstanceOf(DeleteResult.Conflict.class, vev.write(TENANT_7, tx -> tx.entities().delete(
                new DeleteTarget<>(IdentityCounterVev.INSTANCE, row.id(), 1))));
        UUID earlier = id("recoverable-delete-conflict");
        vev.write(TENANT_7, tx -> {
            tx.entities().insert(AccountVev.INSTANCE, account(earlier, 7, 0, "delete-conflict@example.test", "1.0000"));
            assertInstanceOf(DeleteResult.Conflict.class, tx.entities().delete(new DeleteTarget<>(IdentityCounterVev.INSTANCE, row.id(), 1)));
            assertInstanceOf(DeleteResult.Missing.class, tx.entities().delete(new DeleteTarget<>(IdentityCounterVev.INSTANCE, -1, 0)));
            return null;
        });
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlier))).isPresent());
        assertEquals(new DeleteResult.Deleted<>(target), vev.write(TENANT_7, tx -> tx.entities().delete(target)));
        assertInstanceOf(DeleteResult.Missing.class, vev.write(TENANT_7, tx -> tx.entities().delete(target)));
        var next = vev.write(TENANT_7, tx -> tx.entities().create(IdentityCounterVev.INSTANCE, new IdentityCounterVev.New()));
        assertTrue(next.id() > row.id());
        database.setCounterVersion(next.id(), Integer.MAX_VALUE);
        assertInstanceOf(DeleteResult.Deleted.class, vev.write(TENANT_7, tx -> tx.entities().delete(
                new DeleteTarget<>(IdentityCounterVev.INSTANCE, next.id(), Integer.MAX_VALUE))));
    }

    @Test
    void deletionBatchUsesOneStatementAndPreservesOrderAtTheMaximumBound() {
        var count = new AtomicInteger();
        var authority = IntegrationModelVev.newTenantAuthority();
        var runtime = new PgVev<>(DeleteObservationDataSource.observe(database.applicationDataSource(), count, ""),
                IntegrationModelVev.POSTGRES, authority);
        var rows = vev.write(TENANT_7, tx -> tx.entities().createMultiple(IdentityCounterVev.INSTANCE,
                Batch.copyOf(java.util.Collections.nCopies(1000, new IdentityCounterVev.New()))));
        var targets = Batch.copyOf(rows.values().reversed().stream()
                .map(row -> new DeleteTarget<>(IdentityCounterVev.INSTANCE, row.id(), row.version())).toList());
        var results = runtime.write(authority.scope(7), tx -> {
            assertTrue(tx.entities().deleteMultiple(IdentityCounterVev.INSTANCE, Batch.empty()).isEmpty());
            assertEquals(0, count.get());
            assertThrows(IllegalArgumentException.class, () -> tx.entities().deleteMultiple(IdentityCounterVev.INSTANCE,
                    Batch.copyOf(List.of(targets.get(0), targets.get(0)))));
            assertThrows(IllegalArgumentException.class, () -> tx.entities().delete(
                    new DeleteTarget<>(IdentityCounterVev.INSTANCE, targets.get(0).value(), -1)));
            assertEquals(0, count.get());
            return tx.entities().deleteMultiple(IdentityCounterVev.INSTANCE, targets);
        });
        assertEquals(1, count.get());
        assertEquals(targets.values(), results.values().stream().map(DeleteResult.Deleted::target).toList());
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanById(IdentityCounterVev.INSTANCE,
                new QueryLimit(1000)))).values().isEmpty());
    }

    @Test
    void staleMissingAndForeignTenantDeleteBatchesRollBackAllEarlierMutations() {
        for (String failure : List.of("stale", "missing", "tenant")) {
            var rows = vev.write(TENANT_7, tx -> tx.entities().createMultiple(IdentityCounterVev.INSTANCE,
                    Batch.copyOf(java.util.Collections.nCopies(3, new IdentityCounterVev.New()))));
            var foreign = vev.write(TENANT_8, tx -> tx.entities().create(IdentityCounterVev.INSTANCE, new IdentityCounterVev.New()));
            var valid = new DeleteTarget<>(IdentityCounterVev.INSTANCE, rows.get(1).id(), 0);
            var bad = new DeleteTarget<>(IdentityCounterVev.INSTANCE,
                    failure.equals("missing") ? -1 : failure.equals("tenant") ? foreign.id() : rows.get(2).id(),
                    failure.equals("stale") ? 1 : 0);
            UUID earlier = id("atomic-delete-" + failure);
            assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> {
                tx.entities().insert(AccountVev.INSTANCE, account(earlier, 7, 0, failure + "@example.test", "1.0000"));
                assertInstanceOf(DeleteResult.Deleted.class, tx.entities().delete(new DeleteTarget<>(IdentityCounterVev.INSTANCE, rows.get(0).id(), 0)));
                assertThrows(IllegalStateException.class, () -> tx.entities().deleteMultiple(IdentityCounterVev.INSTANCE,
                        Batch.copyOf(List.of(valid, bad))));
                assertThrows(IllegalStateException.class, () -> tx.entities().find(IdentityCounterVev.INSTANCE.key(rows.get(0).id())));
                return null;
            }));
            assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlier))).isEmpty());
            for (var row : rows) assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(IdentityCounterVev.INSTANCE.key(row.id()))).isPresent());
            assertTrue(vev.read(TENANT_8, tx -> tx.entities().find(IdentityCounterVev.INSTANCE.key(foreign.id()))).isPresent());
        }
    }

    @Test
    void deletionKeepsImmediateForeignKeysAndRollsBackEarlierWrites() {
        var parent = vev.write(TENANT_7, tx -> tx.entities().create(IdentityEntryVev.INSTANCE, new IdentityEntryVev.New("parent", null, null)));
        var child = vev.write(TENANT_7, tx -> tx.entities().create(IdentityEventVev.INSTANCE, new IdentityEventVev.New("child", parent.id())));
        UUID earlier = id("delete-foreign-key");
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> {
            tx.entities().insert(AccountVev.INSTANCE, account(earlier, 7, 0, "fk@example.test", "1.0000"));
            assertThrows(IllegalStateException.class, () -> tx.entities().delete(new DeleteTarget<>(IdentityEntryVev.INSTANCE, parent.id(), parent.version())));
            return null;
        }));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlier))).isEmpty());
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(IdentityEntryVev.INSTANCE.key(parent.id()))).isPresent());
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(IdentityEventVev.INSTANCE.key(child.id()))).isPresent());
        var free = vev.write(TENANT_7, tx -> tx.entities().create(IdentityEntryVev.INSTANCE, new IdentityEntryVev.New("free", null, null)));
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> tx.entities().deleteMultiple(IdentityEntryVev.INSTANCE,
                Batch.copyOf(List.of(new DeleteTarget<>(IdentityEntryVev.INSTANCE, free.id(), free.version()),
                        new DeleteTarget<>(IdentityEntryVev.INSTANCE, parent.id(), parent.version()))))));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(IdentityEntryVev.INSTANCE.key(free.id()))).isPresent());
        assertInstanceOf(DeleteResult.Deleted.class, vev.write(TENANT_7, tx -> tx.entities().delete(
                new DeleteTarget<>(IdentityEntryVev.INSTANCE, free.id(), free.version()))));
    }

    @Test
    void kotlinDeleteTargetsAndRowBoundsWorkInBothTransferModes() {
        for (boolean binary : List.of(false, true)) {
            var authority = IntegrationModelVev.newTenantAuthority();
            var runtime = new PgVev<>(database.applicationDataSource(binary), IntegrationModelVev.POSTGRES, authority);
            var rows = runtime.write(authority.scope(7), tx -> tx.entities().createMultiple(KotlinIdentityVev.INSTANCE,
                    Batch.copyOf(java.util.Collections.nCopies(8, new KotlinIdentityVev.New("delete", null)))));
            var targets = Batch.copyOf(rows.values().reversed().stream()
                    .map(row -> new DeleteTarget<>(KotlinIdentityVev.INSTANCE, row.id(), row.version())).toList());
            var results = runtime.write(authority.scope(7), tx -> {
                assertThrows(IllegalArgumentException.class, () -> tx.entities().deleteMultiple(KotlinIdentityVev.INSTANCE,
                        Batch.copyOf(java.util.Collections.nCopies(9, targets.get(0)))));
                return tx.entities().deleteMultiple(KotlinIdentityVev.INSTANCE, targets);
            });
            assertEquals(targets.values(), results.values().stream().map(DeleteResult.Deleted::target).toList());
        }
    }

    @Test
    void deletionPrivilegesMustMatchTheCapabilityWithoutGrantOptions() throws SQLException {
        try {
            for (String variant : List.of("missing", "undeclared", "grantOption")) {
                database.deletionPrivilege(variant);
                assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()), variant);
            }
        } finally {
            database.deletionPrivilege("valid");
        }
        assertDoesNotThrow(() -> runtime(database.applicationDataSource()));
    }

    @Test
    void deleteArrayCleanupFailureRollsBackTheDeletion() {
        var row = vev.write(TENANT_7, tx -> tx.entities().create(IdentityCounterVev.INSTANCE, new IdentityCounterVev.New()));
        var authority = IntegrationModelVev.newTenantAuthority();
        var failures = new AtomicInteger();
        var runtime = new PgVev<>(arrayCleanupFailureDataSource(database.applicationDataSource(), failures),
                IntegrationModelVev.POSTGRES, authority);
        assertThrows(IllegalStateException.class, () -> runtime.write(authority.scope(7), tx ->
                tx.entities().deleteMultiple(IdentityCounterVev.INSTANCE,
                        Batch.one(new DeleteTarget<>(IdentityCounterVev.INSTANCE, row.id(), 0)))));
        assertEquals(1, failures.get());
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(IdentityCounterVev.INSTANCE.key(row.id()))).isPresent());
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
    void enumNamesRoundTripThroughBatchesUpdatesAndTypedIndexQueries() {
        var first = new WorkItem(id("enum-first"), 7, 0L, WorkState.OPEN, null);
        var second = new WorkItem(id("enum-second"), 7, 0L, null, null);
        var input = Batch.copyOf(List.of(first, second));
        var stored = vev.write(TENANT_7, tx -> tx.entities().insertMultiple(WorkItemVev.INSTANCE, input));
        assertEquals(input, stored);
        assertEquals(List.of(first), vev.read(TENANT_7, tx -> tx.entities().many(
                PgQueries.equal(WorkItemVev.STATE, WorkState.OPEN, new QueryLimit(10)))).values());
        assertEquals(List.of(second), vev.read(TENANT_7, tx -> tx.entities().many(
                PgQueries.isNull(WorkItemVev.STATE, new QueryLimit(10)))).values());
        assertTrue(vev.read(TENANT_8, tx -> tx.entities().many(
                PgQueries.equal(WorkItemVev.STATE, WorkState.OPEN, new QueryLimit(10)))).values().isEmpty());
        var changes = Batch.copyOf(List.of(
                new WorkItem(first.id(), 7, 0L, WorkState.CLOSED, null),
                new WorkItem(second.id(), 7, 0L, WorkState.OPEN, null)));
        var updated = vev.write(TENANT_7, tx -> tx.entities().updateMultiple(WorkItemVev.INSTANCE, changes));
        assertEquals(new WorkItem(first.id(), 7, 1L, WorkState.CLOSED, null), updated.get(0).entity());
        assertEquals(new WorkItem(second.id(), 7, 1L, WorkState.OPEN, null), updated.get(1).entity());
        assertEquals(List.of(updated.get(1).entity()), vev.read(TENANT_7, tx -> tx.entities().many(
                PgQueries.equal(WorkItemVev.STATE, WorkState.OPEN, new QueryLimit(10)))).values());
    }

    @Test
    void unknownDatabaseEnumNamePoisonsAndRollsBackTheWholeTransaction() throws SQLException {
        var work = new WorkItem(id("enum-corrupt"), 7, 0L, WorkState.OPEN, null);
        vev.write(TENANT_7, tx -> tx.entities().insert(WorkItemVev.INSTANCE, work));
        database.corruptWorkState(work.id());
        UUID accountId = id("enum-rollback");
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> {
            tx.entities().insert(AccountVev.INSTANCE, account(accountId, 7, 0, "enum@example.test", "1.0000"));
            assertThrows(IllegalStateException.class, () -> tx.entities().find(WorkItemVev.INSTANCE.key(work.id())));
            return null;
        }));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(accountId))).isEmpty());
    }

    @Test
    void persistenceUsesColumnsWithoutCallingEntityEqualityHashingOrRendering() {
        SnapshotProbe single = vev.write(TENANT_7,
                tx -> tx.entities().insert(SnapshotProbeVev.INSTANCE, new SnapshotProbe(1, 7, 0, "first")));
        assertEquals("first", single.value());
        var inserted = vev.write(TENANT_7, tx -> tx.entities().insertMultiple(SnapshotProbeVev.INSTANCE,
                Batch.copyOf(List.of(new SnapshotProbe(2, 7, 0, "second"), new SnapshotProbe(3, 7, 0, "third")))));
        assertEquals(List.of(2L, 3L), inserted.values().stream().map(SnapshotProbe::id).toList());
        var updated = vev.write(TENANT_7, tx -> tx.entities().update(SnapshotProbeVev.INSTANCE,
                new SnapshotProbe(1, 7, 0, "changed")));
        assertEquals("changed", ((MutationResult.Applied<?, SnapshotProbe, ?, ?>) updated).entity().value());
        var batch = vev.write(TENANT_7, tx -> tx.entities().updateMultiple(SnapshotProbeVev.INSTANCE,
                Batch.copyOf(List.of(new SnapshotProbe(3, 7, 0, "third changed"), new SnapshotProbe(2, 7, 0, "second changed")))));
        assertEquals(1L, batch.get(0).entity().version());
        assertEquals(3L, batch.get(0).entity().id());
        assertEquals("changed", vev.read(TENANT_7,
                tx -> tx.entities().find(SnapshotProbeVev.INSTANCE.key(1L))).orElseThrow().value());
        var page = vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanById(SnapshotProbeVev.INSTANCE, new QueryLimit(10))));
        assertEquals(List.of(1L, 2L, 3L), page.values().stream().map(SnapshotProbe::id).toList());
        assertTrue(vev.read(TENANT_8, tx -> tx.entities().find(SnapshotProbeVev.INSTANCE.key(1L))).isEmpty());
    }

    @Test
    void explicitlyGlobalAssignedIdentifiersRemainTenantFiltered() {
        vev.write(TENANT_7, tx -> tx.entities().insert(SnapshotProbeVev.INSTANCE, new SnapshotProbe(42, 7, 0, "tenant seven")));
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_8, tx -> tx.entities().insert(
                SnapshotProbeVev.INSTANCE, new SnapshotProbe(42, 8, 0, "collision"))));
        assertTrue(vev.read(TENANT_8, tx -> tx.entities().find(SnapshotProbeVev.INSTANCE.key(42L))).isEmpty());
        assertTrue(vev.read(TENANT_8, tx -> tx.entities().many(PgQueries.equal(SnapshotProbeVev.ID, 42L,
                new QueryLimit(2)))).values().isEmpty());
        var own = vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.equal(SnapshotProbeVev.ID, 42L,
                new QueryLimit(2))));
        assertEquals(1, own.values().size());
        assertEquals("tenant seven", own.values().getFirst().value());
    }

    @Test
    void bootstrapAttestsGlobalPrimaryKeysTheirTraversalIndexesAndAlternateReferenceKeys() throws SQLException {
        for (String variant : List.of("tenantFirst", "idTenant", "included", "deferred")) {
            try {
                database.identityEntryPrimaryKey(variant);
                assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()), variant);
            } finally {
                database.identityEntryPrimaryKey("valid");
            }
        }
        for (String variant : List.of("missing", "reversed", "duplicate", "partial", "unique")) {
            try {
                database.identityEntryTraversalIndex(variant);
                assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()), variant);
            } finally {
                database.identityEntryTraversalIndex("valid");
            }
        }
        try {
            database.identityEntryAlternateKeyTenantFirst(true);
            assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()));
        } finally {
            database.identityEntryAlternateKeyTenantFirst(false);
        }
        assertDoesNotThrow(() -> runtime(database.applicationDataSource()));
    }

    @Test
    void globalIdentityPrimaryKeysRetainTenantScopedReferencesAndIndexedPagination() {
        var entries = vev.write(TENANT_7, tx -> tx.entities().createMultiple(IdentityEntryVev.INSTANCE,
                Batch.copyOf(List.of(new IdentityEntryVev.New("first", null, null),
                        new IdentityEntryVev.New("second", null, null), new IdentityEntryVev.New("third", null, null)))));
        var child = vev.write(TENANT_7, tx -> tx.entities().create(IdentityEventVev.INSTANCE,
                new IdentityEventVev.New("child", entries.get(0).id())));
        assertEquals(entries.get(0).id(), child.entryId());
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_8, tx -> tx.entities().create(IdentityEventVev.INSTANCE,
                new IdentityEventVev.New("foreign child", entries.get(0).id()))));
        var firstPage = vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanById(IdentityEntryVev.INSTANCE, new QueryLimit(2))));
        assertEquals(entries.values().subList(0, 2), firstPage.values());
        assertTrue(firstPage.hasMore());
        var nextPage = vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanByIdAfter(
                IdentityEntryVev.INSTANCE.key(entries.get(1).id()), new QueryLimit(2))));
        assertEquals(List.of(entries.get(2)), nextPage.values());
        assertFalse(nextPage.hasMore());
        assertEquals(List.of(entries.get(0)), vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.equal(
                IdentityEntryVev.ID, entries.get(0).id(), new QueryLimit(2)))).values());
        assertTrue(vev.read(TENANT_8, tx -> tx.entities().many(PgQueries.equal(
                IdentityEntryVev.ID, entries.get(0).id(), new QueryLimit(2)))).values().isEmpty());
        assertTrue(vev.read(TENANT_8, tx -> tx.entities().find(IdentityEventVev.INSTANCE.key(child.id()))).isEmpty());
    }

    @Test
    void bootstrapRequiresTheDeclaredIdentifierFirstReferenceOrder() throws SQLException {
        try {
            database.identityReferenceTenantFirst(true);
            assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()));
        } finally {
            database.identityReferenceTenantFirst(false);
        }
        assertDoesNotThrow(() -> runtime(database.applicationDataSource()));
        UUID parent = id("identifier-first-parent");
        insert(account(parent, 7, 0, "parent@example.test", "1.0000"), TENANT_7);
        var child = vev.write(TENANT_7, tx -> tx.entities().create(IdentityEntryVev.INSTANCE,
                new IdentityEntryVev.New("child", null, parent)));
        assertEquals(parent, child.accountId());
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_8, tx -> tx.entities().create(IdentityEntryVev.INSTANCE,
                new IdentityEntryVev.New("foreign child", null, parent))));
    }

    @Test
    void jakartaFacadeRejectsIdentityInsertionWithoutPoisoningPreSqlValidation() {
        var snapshot = new IdentityEntry(1L, 7, (short) 0, "identified", null, null);
        Account assigned = account(id("agent-after-identity-rejection"), 7, 0, "assigned@example.test", "1.0000");
        VevEntityAgents.callInTransaction(vev, TENANT_7, agent -> {
            assertThrows(UnsupportedOperationException.class, () -> agent.insert(snapshot));
            assertThrows(UnsupportedOperationException.class, () -> agent.insertMultiple(List.of(snapshot)));
            agent.insert(assigned);
            return null;
        });
        assertEquals(assigned, find(assigned.id(), TENANT_7));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanById(IdentityEntryVev.INSTANCE,
                new QueryLimit(10)))).values().isEmpty());
    }

    @Test
    void kotlinIdentityCreationPreservesNullabilityAndExplicitCopyUpdates() {
        var created = vev.write(TENANT_7, tx -> tx.entities().createMultiple(KotlinIdentityVev.INSTANCE,
                Batch.copyOf(List.of(new KotlinIdentityVev.New("required", null), new KotlinIdentityVev.New("second", "note")))));
        KotlinIdentity first = created.get(0);
        assertTrue(first.id() > 0);
        assertEquals(7, first.tenantId());
        assertEquals(0L, first.version());
        assertEquals("required", first.label());
        assertNull(first.note());
        assertEquals("note", created.get(1).note());
        var expected = first.copy(first.id(), 7, 0L, "changed", null);
        var changed = vev.write(TENANT_7, tx -> tx.entities().updateMultiple(KotlinIdentityVev.INSTANCE, Batch.one(expected)));
        assertEquals(first.copy(first.id(), 7, 1L, "changed", null), changed.get(0).entity());
        assertTrue(vev.read(TENANT_8, tx -> tx.entities().find(KotlinIdentityVev.INSTANCE.key(first.id()))).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> vev.write(TENANT_7,
                tx -> tx.entities().create(KotlinIdentityVev.INSTANCE, new KotlinIdentityVev.New(null, null))));
    }

    @Test
    void identityCreationUsesOneStatementAndValidatesTheWholeInputBeforeSql() {
        var authority = IntegrationModelVev.newTenantAuthority();
        var count = new AtomicInteger();
        var runtime = new PgVev<>(IdentityObservationDataSource.observe(database.applicationDataSource(), count, ""),
                IntegrationModelVev.POSTGRES, authority);
        var scope = authority.scope(7);
        runtime.write(scope, tx -> {
            assertTrue(tx.entities().createMultiple(IdentityEntryVev.INSTANCE, Batch.empty()).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> tx.entities().createMultiple(IdentityEntryVev.INSTANCE,
                    Batch.copyOf(List.of(new IdentityEntryVev.New("valid", null, null), new IdentityEntryVev.New(null, null, null)))));
            assertEquals(0, count.get());
            var inputs = java.util.stream.IntStream.range(0, 1000)
                    .mapToObj(row -> new IdentityEntryVev.New("row " + row, null, null)).toList();
            var created = tx.entities().createMultiple(IdentityEntryVev.INSTANCE, Batch.copyOf(inputs));
            assertEquals(1000, created.size());
            assertEquals("row 999", created.get(999).label());
            assertEquals(1, count.get());
            return null;
        });
    }

    @Test
    void unexpectedIdentityResultsPoisonAndRollbackTheCompleteTransaction() {
        for (String corruption : List.of("ordinal", "key", "tenant", "version", "value")) {
            var authority = IntegrationModelVev.newTenantAuthority();
            var count = new AtomicInteger();
            var runtime = new PgVev<>(IdentityObservationDataSource.observe(database.applicationDataSource(), count, corruption),
                    IntegrationModelVev.POSTGRES, authority);
            UUID earlier = id("identity-result-" + corruption);
            assertThrows(IllegalStateException.class, () -> runtime.write(authority.scope(7), tx -> {
                tx.entities().insert(AccountVev.INSTANCE, account(earlier, 7, 0, "earlier@example.test", "1.0000"));
                assertThrows(IllegalStateException.class, () -> tx.entities().create(IdentityEntryVev.INSTANCE,
                        new IdentityEntryVev.New("input", null, null)));
                assertThrows(IllegalStateException.class, () -> tx.entities().find(AccountVev.INSTANCE.key(earlier)));
                return null;
            }), corruption);
            assertEquals(1, count.get());
            assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlier))).isEmpty());
            assertTrue(vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanById(IdentityEntryVev.INSTANCE,
                    new QueryLimit(10)))).values().isEmpty());
        }
    }

    @Test
    void identityArrayCleanupFailureRollsBackAnOtherwiseSuccessfulCreation() {
        var authority = IntegrationModelVev.newTenantAuthority();
        var failures = new AtomicInteger();
        var runtime = new PgVev<>(arrayCleanupFailureDataSource(database.applicationDataSource(), failures),
                IntegrationModelVev.POSTGRES, authority);
        assertThrows(IllegalStateException.class, () -> runtime.write(authority.scope(7), tx ->
                tx.entities().create(IdentityEntryVev.INSTANCE, new IdentityEntryVev.New("cleanup", null, null))));
        assertEquals(1, failures.get());
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanById(IdentityEntryVev.INSTANCE,
                new QueryLimit(10)))).values().isEmpty());
    }

    @Test
    void identityCreationAcceptsBothIdentityModesAtEveryIntegerWidth() throws SQLException {
        try {
            database.identityMode("BY DEFAULT");
            assertDoesNotThrow(() -> runtime(database.applicationDataSource()));
            identityCreationHandlesAllIntegerWidthsAppendOnlyAndEmptyInputs();
            var entry = vev.write(TENANT_7, tx -> tx.entities().create(IdentityEntryVev.INSTANCE,
                    new IdentityEntryVev.New("by default", null, null)));
            assertTrue(entry.id() > 0);
        } finally {
            database.identityMode("ALWAYS");
        }
        assertDoesNotThrow(() -> runtime(database.applicationDataSource()));
    }

    @Test
    void bootstrapAttestsIdentitySequenceShapeAndLeastPrivilege() throws SQLException {
        for (String variant : List.of("increment", "minimum", "maximum", "start", "cycle", "type",
                "missingUsage", "select", "update", "grantOption")) {
            try {
                database.identitySequenceVariant(variant);
                assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()), variant);
            } finally {
                database.identitySequenceVariant("valid");
            }
        }
        try {
            database.identitySequenceVariant("cache");
            assertDoesNotThrow(() -> runtime(database.applicationDataSource()));
        } finally {
            database.identitySequenceVariant("valid");
        }
        assertDoesNotThrow(() -> runtime(database.applicationDataSource()));
    }

    @Test
    void identityExhaustionPoisonsTheTransactionAndRollsBackEarlierWrites() throws SQLException {
        UUID earlier = id("identity-exhaustion");
        try {
            database.restartSmallIdentity(Short.MAX_VALUE);
            assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> {
                tx.entities().insert(AccountVev.INSTANCE, account(earlier, 7, 0, "earlier@example.test", "1.0000"));
                assertThrows(IllegalStateException.class, () -> tx.entities().createMultiple(IdentityEventVev.INSTANCE,
                        Batch.copyOf(List.of(new IdentityEventVev.New("last", null), new IdentityEventVev.New("exhausted", null)))));
                assertThrows(IllegalStateException.class, () -> tx.entities().find(AccountVev.INSTANCE.key(earlier)));
                return null;
            }));
            assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlier))).isEmpty());
            assertTrue(vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanById(IdentityEventVev.INSTANCE,
                    new QueryLimit(10)))).values().isEmpty());
        } finally {
            database.restartSmallIdentity(1);
        }
    }

    @Test
    void concurrentIdentityBatchesKeepUniqueKeysAndExactInputCorrelation() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Batch<IdentityEntry>>>();
            for (int task = 0; task < 16; task++) {
                int batchNumber = task;
                tasks.add(executor.submit(() -> vev.write(batchNumber % 2 == 0 ? TENANT_7 : TENANT_8, tx -> {
                    var inputs = java.util.stream.IntStream.range(0, 20)
                            .mapToObj(row -> new IdentityEntryVev.New(batchNumber + ":" + row, null, null)).toList();
                    return tx.entities().createMultiple(IdentityEntryVev.INSTANCE, Batch.copyOf(inputs));
                })));
            }
            var keys = new java.util.HashSet<Long>();
            for (int task = 0; task < tasks.size(); task++) {
                Batch<IdentityEntry> rows = tasks.get(task).get(20, TimeUnit.SECONDS);
                for (int row = 0; row < rows.size(); row++) {
                    IdentityEntry entry = rows.get(row);
                    assertTrue(keys.add(entry.id()));
                    assertEquals(task + ":" + row, entry.label());
                    assertEquals(task % 2 == 0 ? 7 : 8, entry.tenantId());
                }
            }
            assertEquals(320, keys.size());
        }
    }

    @Test
    void identityCreationCorrelatesDuplicatePayloadsAndKeepsSnapshotsTenantBound() {
        var inputs = Batch.copyOf(List.of(new IdentityEntryVev.New("duplicate", null, null),
                new IdentityEntryVev.New("last", null, null), new IdentityEntryVev.New("duplicate", null, null)));
        var created = vev.write(TENANT_7, tx -> tx.entities().createMultiple(IdentityEntryVev.INSTANCE, inputs));
        assertEquals(List.of("duplicate", "last", "duplicate"), created.values().stream().map(IdentityEntry::label).toList());
        assertEquals(3, created.values().stream().map(IdentityEntry::id).distinct().count());
        for (IdentityEntry entry : created) {
            assertTrue(entry.id() > 0);
            assertEquals(7, entry.tenantId());
            assertEquals((short) 0, entry.version());
            assertNull(entry.code());
            assertEquals(entry, vev.read(TENANT_7, tx -> tx.entities().find(IdentityEntryVev.INSTANCE.key(entry.id()))).orElseThrow());
            assertTrue(vev.read(TENANT_8, tx -> tx.entities().find(IdentityEntryVev.INSTANCE.key(entry.id()))).isEmpty());
        }
        IdentityEntry first = created.get(0);
        var changed = new IdentityEntry(first.id(), 7, first.version(), "changed", null, null);
        var result = vev.write(TENANT_7, tx -> tx.entities().update(IdentityEntryVev.INSTANCE, changed));
        var updated = ((MutationResult.Applied<?, IdentityEntry, ?, ?>) result).entity();
        assertEquals(first.id(), updated.id());
        assertEquals((short) 1, updated.version());
        assertEquals("changed", updated.label());
        var other = vev.write(TENANT_8, tx -> tx.entities().create(IdentityEntryVev.INSTANCE, inputs.get(0)));
        assertEquals(8, other.tenantId());
        assertFalse(created.values().stream().anyMatch(entry -> entry.id().equals(other.id())));
        assertTrue(vev.write(TENANT_7, tx -> tx.entities().createMultiple(IdentityEntryVev.INSTANCE, Batch.empty())).isEmpty());
    }

    @Test
    void identityCreationHandlesAllIntegerWidthsAppendOnlyAndEmptyInputs() {
        var counters = vev.write(TENANT_7, tx -> tx.entities().createMultiple(IdentityCounterVev.INSTANCE,
                Batch.copyOf(List.of(new IdentityCounterVev.New(), new IdentityCounterVev.New()))));
        assertEquals(2, counters.size());
        assertTrue(counters.get(0).id() > 0 && counters.get(1).id() > counters.get(0).id());
        assertEquals(0, counters.get(0).version());
        var events = vev.write(TENANT_7, tx -> tx.entities().createMultiple(IdentityEventVev.INSTANCE,
                Batch.copyOf(List.of(new IdentityEventVev.New(null, null), new IdentityEventVev.New("event", null)))));
        assertEquals(2, events.size());
        assertNull(events.get(0).message());
        assertEquals("event", events.get(1).message());
        assertTrue(events.get(0).id() > 0 && events.get(1).id() > events.get(0).id());
        assertTrue(vev.read(TENANT_8, tx -> tx.entities().find(IdentityEventVev.INSTANCE.key(events.get(0).id()))).isEmpty());
    }

    @Test
    void binarySnapshotsSupportNullableArraysIndexesAndCompleteBatchWrites() {
        byte[] everyByte = new byte[256];
        for (int index = 0; index < everyByte.length; index++) everyByte[index] = (byte) index;
        byte[] maximum = new byte[65536];
        new java.util.Random(1701).nextBytes(maximum);
        var rows = List.of(new BinarySample(1, 7, 0, null, null),
                new BinarySample(2, 7, 0, Binary.empty(), Binary.empty()),
                new BinarySample(3, 7, 0, Binary.copyOf(everyByte), Binary.fromHex("00ff")),
                new BinarySample(4, 7, 0, Binary.copyOf(maximum), Binary.fromHex("80")));
        everyByte[0] = 99;
        assertEquals(rows, vev.write(TENANT_7, tx -> tx.entities().insertMultiple(BinarySampleVev.INSTANCE, Batch.copyOf(rows))).values());
        var lookedUp = vev.read(TENANT_7, tx -> tx.entities().findMultiple(BinarySampleVev.INSTANCE,
                Batch.copyOf(List.of(4, 1, 3, 2, 99, 4))));
        assertEquals(rows.get(3), ((EntityLookup.Found<?, ?, ?>) lookedUp.get(0)).entity());
        assertInstanceOf(EntityLookup.Missing.class, lookedUp.get(4));
        assertEquals(List.of(rows.get(2)), vev.read(TENANT_7, tx -> tx.entities().many(
                PgQueries.equal(BinarySampleVev.DIGEST, Binary.fromHex("00FF"), new QueryLimit(32)))).values());
        assertEquals(List.of(rows.get(0)), vev.read(TENANT_7, tx -> tx.entities().many(
                PgQueries.isNull(BinarySampleVev.DIGEST, new QueryLimit(32)))).values());
        var updates = rows.stream().map(row -> new BinarySample(row.id(), 7, 0, Binary.empty(), row.digest())).toList();
        assertEquals(4, vev.write(TENANT_7, tx -> tx.entities().updateMultiple(BinarySampleVev.INSTANCE, Batch.copyOf(updates))).size());
        var otherTenant = new BinarySample(3, 8, 0, Binary.fromHex("01"), Binary.fromHex("00ff"));
        vev.write(TENANT_8, tx -> tx.entities().insert(BinarySampleVev.INSTANCE, otherTenant));
        assertEquals(List.of(otherTenant), vev.read(TENANT_8, tx -> tx.entities().many(
                PgQueries.equal(BinarySampleVev.DIGEST, Binary.fromHex("00ff"), new QueryLimit(32)))).values());
        UUID earlier = id("binary-unique-earlier");
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> {
            tx.entities().insert(AccountVev.INSTANCE, account(earlier, 7, 0, "binary-rollback@example.test", "1.0000"));
            return tx.entities().insertMultiple(BinarySampleVev.INSTANCE, Batch.copyOf(List.of(
                    new BinarySample(5, 7, 0, Binary.empty(), Binary.fromHex("1234")),
                    new BinarySample(6, 7, 0, Binary.empty(), Binary.fromHex("00ff")))));
        }));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlier))).isEmpty());
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(BinarySampleVev.INSTANCE.key(5))).isEmpty());
    }

    @Test
    void binaryIdentityCreationHandlesTwentyMiBAndKotlinNullableArrays() {
        byte[] bytes = new byte[20 * 1024 * 1024];
        new java.util.Random(827).nextBytes(bytes);
        Binary content = Binary.copyOf(bytes);
        var created = vev.write(TENANT_7, tx -> tx.entities().create(BinaryAssetVev.INSTANCE, new BinaryAssetVev.New(content)));
        assertEquals(content, created.content());
        assertEquals(created, vev.read(TENANT_7, tx -> tx.entities().find(BinaryAssetVev.INSTANCE.key(created.id()))).orElseThrow());
        assertTrue(vev.read(TENANT_8, tx -> tx.entities().find(BinaryAssetVev.INSTANCE.key(created.id()))).isEmpty());
        var updated = vev.write(TENANT_7, tx -> tx.entities().update(BinaryAssetVev.INSTANCE,
                new BinaryAsset(created.id(), 7, 0, Binary.empty())));
        assertEquals(Binary.empty(), ((MutationResult.Applied<?, BinaryAsset, ?, ?>) updated).entity().content());
        Binary excessive = Binary.copyOf(new byte[20 * 1024 * 1024 + 1]);
        assertThrows(IllegalArgumentException.class, () -> vev.write(TENANT_7, tx -> tx.entities().create(BinaryAssetVev.INSTANCE,
                new BinaryAssetVev.New(excessive))));
        var kotlin = vev.write(TENANT_7, tx -> tx.entities().createMultiple(KotlinBinaryVev.INSTANCE, Batch.copyOf(List.of(
                new KotlinBinaryVev.New(null), new KotlinBinaryVev.New(Binary.empty()),
                new KotlinBinaryVev.New(Binary.fromHex("0001feff")), new KotlinBinaryVev.New(null)))));
        assertNull(kotlin.get(0).payload());
        assertEquals(Binary.empty(), kotlin.get(1).payload());
        assertEquals(Binary.fromHex("0001feff"), kotlin.get(2).payload());
        assertNull(kotlin.get(3).payload());
        KotlinBinary first = kotlin.get(0);
        KotlinBinary replacement = first.copy(first.id(), first.tenantId(), first.version(), Binary.fromHex("20"));
        var changed = vev.write(TENANT_7, tx -> tx.entities().updateMultiple(KotlinBinaryVev.INSTANCE, Batch.one(replacement)));
        assertEquals(Binary.fromHex("20"), changed.get(0).entity().payload());
    }

    @Test
    void binaryBoundsFailBeforeSqlAndReturnedSnapshotsDoNotAliasDriverBuffers() {
        var count = new AtomicInteger();
        var authority = IntegrationModelVev.newTenantAuthority();
        var runtime = new PgVev<>(entityStatementCountingDataSource(database.applicationDataSource(), count), IntegrationModelVev.POSTGRES, authority);
        count.set(0);
        var valid = new BinarySample(1, 7, 0, Binary.fromHex("12345678"), null);
        runtime.write(authority.scope(7), tx -> {
            assertThrows(IllegalArgumentException.class, () -> tx.entities().insert(BinarySampleVev.INSTANCE,
                    new BinarySample(2, 7, 0, Binary.copyOf(new byte[65537]), null)));
            assertThrows(IllegalArgumentException.class, () -> tx.entities().insert(BinarySampleVev.INSTANCE,
                    new BinarySample(2, 7, 0, null, Binary.copyOf(new byte[33]))));
            assertEquals(0, count.get());
            return tx.entities().insert(BinarySampleVev.INSTANCE, valid);
        });
        var borrowed = new AtomicReference<byte[]>();
        var readAuthority = IntegrationModelVev.newTenantAuthority();
        var reader = new PgVev<>(binaryResultDataSource(database.applicationDataSource(), borrowed, false), IntegrationModelVev.POSTGRES, readAuthority);
        var snapshot = reader.read(readAuthority.scope(7), tx -> tx.entities().find(BinarySampleVev.INSTANCE.key(1))).orElseThrow();
        assertNotNull(borrowed.get());
        java.util.Arrays.fill(borrowed.get(), (byte) 0);
        assertEquals(valid, snapshot);
    }

    @Test
    void corruptedBinaryResultsAndArrayCleanupFailuresRollbackEarlierWrites() {
        for (boolean corrupt : List.of(false, true)) {
            var authority = IntegrationModelVev.newTenantAuthority();
            var failures = new AtomicInteger();
            DataSource source = corrupt ? binaryResultDataSource(database.applicationDataSource(), new AtomicReference<>(), true)
                    : arrayCleanupFailureDataSource(database.applicationDataSource(), failures);
            var runtime = new PgVev<>(source, IntegrationModelVev.POSTGRES, authority);
            UUID earlier = id("binary-failure-" + corrupt);
            assertThrows(IllegalStateException.class, () -> runtime.write(authority.scope(7), tx -> {
                tx.entities().insert(AccountVev.INSTANCE, account(earlier, 7, 0, "binary-failure@example.test", "1.0000"));
                return tx.entities().insertMultiple(BinarySampleVev.INSTANCE,
                        Batch.one(new BinarySample(1, 7, 0, Binary.fromHex("aabb"), null)));
            }));
            assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlier))).isEmpty());
            assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(BinarySampleVev.INSTANCE.key(1))).isEmpty());
            if (!corrupt) assertTrue(failures.get() > 0);
        }
    }

    @Test
    void boundedTextPreservesUnicodeWhitespaceNullsAndTypedBatchQueries() {
        String maximum = "🙂".repeat(1048576);
        var rows = List.of(new TextDocument(1, 7, 0, maximum, "  trailing  "),
                new TextDocument(2, 7, 0, "e\u0301é\n\t'\\{}", "🙂".repeat(128)),
                new TextDocument(3, 7, 0, "", ""), new TextDocument(4, 7, 0, null, null));
        assertEquals(rows, vev.write(TENANT_7, tx -> tx.entities().insertMultiple(TextDocumentVev.INSTANCE, Batch.copyOf(rows))).values());
        assertEquals(rows.get(0), vev.read(TENANT_7, tx -> tx.entities().find(TextDocumentVev.INSTANCE.key(1))).orElseThrow());
        assertEquals(List.of(rows.get(0)), vev.read(TENANT_7, tx -> tx.entities().many(
                PgQueries.equal(TextDocumentVev.LABEL, "  trailing  ", new QueryLimit(4)))).values());
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().many(
                PgQueries.equal(TextDocumentVev.LABEL, "  trailing", new QueryLimit(4)))).values().isEmpty());
        assertEquals(List.of(rows.get(3)), vev.read(TENANT_7, tx -> tx.entities().many(
                PgQueries.isNull(TextDocumentVev.LABEL, new QueryLimit(4)))).values());
        var page = vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanById(TextDocumentVev.INSTANCE, new QueryLimit(2))));
        assertEquals(rows.subList(0, 2), page.values());
        assertTrue(page.hasMore());
        assertEquals(rows.subList(2, 4), vev.read(TENANT_7, tx -> tx.entities().many(
                PgQueries.scanByIdAfter(TextDocumentVev.INSTANCE.key(2), new QueryLimit(4)))).values());
        var changed = vev.write(TENANT_7, tx -> tx.entities().updateMultiple(TextDocumentVev.INSTANCE, Batch.copyOf(
                rows.stream().map(row -> new TextDocument(row.id(), 7, 0, null, row.label())).toList())));
        assertEquals(4, changed.size());
        assertTrue(changed.values().stream().allMatch(result -> result.entity().version() == 1 && result.entity().body() == null));
        var other = new TextDocument(1, 8, 0, "other", "  trailing  ");
        vev.write(TENANT_8, tx -> tx.entities().insert(TextDocumentVev.INSTANCE, other));
        assertEquals(List.of(other), vev.read(TENANT_8, tx -> tx.entities().many(
                PgQueries.equal(TextDocumentVev.LABEL, "  trailing  ", new QueryLimit(4)))).values());
    }

    @Test
    void kotlinTextIdentityBatchesPreserveNullAndExactCharacterBounds() {
        var inputs = List.of(new KotlinTextVev.New(null), new KotlinTextVev.New(""),
                new KotlinTextVev.New("🙂".repeat(64)), new KotlinTextVev.New(" leading and trailing "));
        var created = vev.write(TENANT_7, tx -> tx.entities().createMultiple(KotlinTextVev.INSTANCE, Batch.copyOf(inputs)));
        for (int index = 0; index < inputs.size(); index++) assertEquals(inputs.get(index).payload(), created.get(index).payload());
        var first = created.get(0);
        var replacement = first.copy(first.id(), first.tenantId(), first.version(), "changed");
        var changed = vev.write(TENANT_7, tx -> tx.entities().update(KotlinTextVev.INSTANCE, replacement));
        assertEquals("changed", ((MutationResult.Applied<?, no.beint.vev.fixtures.KotlinText, ?, ?>) changed).entity().payload());
        assertTrue(vev.read(TENANT_8, tx -> tx.entities().find(KotlinTextVev.INSTANCE.key(first.id()))).isEmpty());
    }

    @Test
    void textValidationRunsBeforeSqlAndConstraintFailuresRollbackEarlierWrites() {
        var count = new AtomicInteger();
        var authority = IntegrationModelVev.newTenantAuthority();
        var runtime = new PgVev<>(entityStatementCountingDataSource(database.applicationDataSource(), count), IntegrationModelVev.POSTGRES, authority);
        count.set(0);
        runtime.write(authority.scope(7), tx -> {
            for (String invalid : List.of("x".repeat(1048577), "\0", "\ud800", "\udc00")) {
                assertThrows(IllegalArgumentException.class, () -> tx.entities().insert(TextDocumentVev.INSTANCE,
                        new TextDocument(1, 7, 0, invalid, null)));
            }
            assertThrows(IllegalArgumentException.class, () -> tx.entities().many(
                    PgQueries.equal(TextDocumentVev.LABEL, "🙂".repeat(129), new QueryLimit(4))));
            assertEquals(0, count.get());
            return tx.entities().insert(TextDocumentVev.INSTANCE, new TextDocument(1, 7, 0, "valid", "unique"));
        });
        UUID earlier = id("text-rollback-earlier");
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> {
            tx.entities().insert(AccountVev.INSTANCE, account(earlier, 7, 0, "text-rollback@example.test", "1.0000"));
            assertThrows(IllegalStateException.class, () -> tx.entities().insertMultiple(TextDocumentVev.INSTANCE,
                    Batch.copyOf(List.of(new TextDocument(2, 7, 0, "would succeed", "new"), new TextDocument(3, 7, 0, "conflicts", "unique")))));
            return null;
        }));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlier))).isEmpty());
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(TextDocumentVev.INSTANCE.key(2))).isEmpty());
    }

    @Test
    void textSchemaRejectsWrongBoundsUnitsTypesAndConstraintStates() throws SQLException {
        for (String variant : List.of("missing", "renamed", "weakened", "tightened", "wrongcolumn", "wrongunits",
                "unvalidated", "unenforced", "noinherit", "extra")) {
            try {
                database.textDocumentBound(variant);
                assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()), variant);
            } finally {
                database.textDocumentBound("valid");
            }
        }
        try {
            database.textDocumentUsesVarchar(true);
            assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()));
        } finally {
            database.textDocumentUsesVarchar(false);
        }
        runtime(database.applicationDataSource());
        SQLException oversized = assertThrows(SQLException.class, () -> database.insertTextBeyondDatabaseBound());
        assertEquals("23514", oversized.getSQLState());
    }

    @Test
    void localTimePreservesMicrosecondsNullableBatchesAndIndexResultsInBothTransferModes() {
        for (boolean binary : List.of(false, true)) {
            var authority = IntegrationModelVev.newTenantAuthority();
            var runtime = new PgVev<>(database.applicationDataSource(binary), IntegrationModelVev.POSTGRES, authority);
            var scope = authority.scope(binary ? 8 : 7);
            var inputs = List.of(new KotlinClockVev.New(null), new KotlinClockVev.New(LocalTime.MIDNIGHT),
                    new KotlinClockVev.New(LocalTime.of(12, 34, 56, 123456000)),
                    new KotlinClockVev.New(LocalTime.of(23, 59, 59, 999999000)));
            var created = runtime.write(scope, tx -> tx.entities().createMultiple(KotlinClockVev.INSTANCE, Batch.copyOf(inputs)));
            for (int index = 0; index < inputs.size(); index++) assertEquals(inputs.get(index).observedAt(), created.get(index).observedAt());
            assertEquals(created.get(3), runtime.read(scope, tx -> tx.entities().find(KotlinClockVev.INSTANCE.key(created.get(3).id()))).orElseThrow());
            assertEquals(List.of(created.get(1)), runtime.read(scope, tx -> tx.entities().many(
                    PgQueries.equal(KotlinClockVev.OBSERVED_AT, LocalTime.MIDNIGHT, new QueryLimit(10)))).values());
            assertEquals(List.of(created.get(0)), runtime.read(scope, tx -> tx.entities().many(
                    PgQueries.isNull(KotlinClockVev.OBSERVED_AT, new QueryLimit(10)))).values());
            var changed = runtime.write(scope, tx -> tx.entities().updateMultiple(KotlinClockVev.INSTANCE,
                    Batch.copyOf(created.values().stream().map(row -> row.copy(row.id(), row.tenantId(), row.version(), LocalTime.NOON)).toList())));
            assertTrue(changed.values().stream().allMatch(result -> result.entity().version() == 1 && result.entity().observedAt().equals(LocalTime.NOON)));
            assertEquals(4, runtime.read(scope, tx -> tx.entities().many(
                    PgQueries.equal(KotlinClockVev.OBSERVED_AT, LocalTime.NOON, new QueryLimit(10)))).values().size());
            var first = changed.get(0).entity();
            var single = runtime.write(scope, tx -> tx.entities().update(KotlinClockVev.INSTANCE, first.copy(first.id(), first.tenantId(), first.version(), null)));
            assertNull(((MutationResult.Applied<?, KotlinClock, ?, ?>) single).entity().observedAt());
        }
    }

    @Test
    void localTimeRejectsLossyInputsBeforeSqlAndUnrepresentableStoredValuesPoisonTransactions() throws SQLException {
        var counter = new AtomicInteger();
        var authority = IntegrationModelVev.newTenantAuthority();
        var runtime = new PgVev<>(entityStatementCountingDataSource(database.applicationDataSource(), counter), IntegrationModelVev.POSTGRES, authority);
        counter.set(0);
        runtime.write(authority.scope(7), tx -> {
            for (LocalTime invalid : List.of(LocalTime.MAX, LocalTime.of(1, 2, 3, 1), LocalTime.of(1, 2, 3, 999999999))) {
                assertThrows(IllegalArgumentException.class, () -> tx.entities().create(KotlinClockVev.INSTANCE, new KotlinClockVev.New(invalid)));
                assertThrows(IllegalArgumentException.class, () -> tx.entities().many(
                        PgQueries.equal(KotlinClockVev.OBSERVED_AT, invalid, new QueryLimit(1))));
            }
            assertEquals(0, counter.get());
            return tx.entities().create(KotlinClockVev.INSTANCE, new KotlinClockVev.New(LocalTime.NOON));
        });
        int invalidKey = database.insertEndOfDayClock();
        for (boolean binary : List.of(false, true)) {
            var readAuthority = IntegrationModelVev.newTenantAuthority();
            var reader = new PgVev<>(database.applicationDataSource(binary), IntegrationModelVev.POSTGRES, readAuthority);
            UUID earlier = id("clock-invalid-" + binary);
            assertThrows(IllegalStateException.class, () -> reader.write(readAuthority.scope(7), tx -> {
                tx.entities().insert(AccountVev.INSTANCE, account(earlier, 7, 0, "clock-failure@example.test", "1.0000"));
                assertThrows(IllegalStateException.class, () -> tx.entities().find(KotlinClockVev.INSTANCE.key(invalidKey)));
                return null;
            }));
            assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlier))).isEmpty());
        }
    }

    @Test
    void localTimeBootstrapRejectsDatabasePrecisionThatWouldRoundValues() throws SQLException {
        try {
            database.clockUsesMilliseconds(true);
            assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()));
        } finally {
            database.clockUsesMilliseconds(false);
        }
        runtime(database.applicationDataSource());
    }

    @Test
    void largeSnapshotsUseDeclaredBatchLimitsAndPreservePagingAndTenantIsolation() {
        assertEquals(8, LargeTextVev.INSTANCE.maximumRows());
        assertEquals(8, KotlinIdentityVev.INSTANCE.maximumRows());
        String body = "🙂".repeat(65535);
        var inputs = java.util.stream.IntStream.rangeClosed(1, 8).mapToObj(index -> new LargeText(index, 7, 0, "bulk", body)).toList();
        var inserted = vev.write(TENANT_7, tx -> tx.entities().insertMultiple(LargeTextVev.INSTANCE, Batch.copyOf(inputs)));
        assertEquals(inputs, inserted.values());
        vev.write(TENANT_7, tx -> tx.entities().insert(LargeTextVev.INSTANCE, new LargeText(9, 7, 0, "bulk", body)));
        var first = vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanById(LargeTextVev.INSTANCE, new QueryLimit(8))));
        assertEquals(8, first.values().size());
        assertTrue(first.hasMore());
        var next = vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanByIdAfter(LargeTextVev.INSTANCE.key(8), new QueryLimit(8))));
        assertEquals(List.of(9), next.values().stream().map(LargeText::id).toList());
        assertFalse(next.hasMore());
        var indexed = vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.equal(LargeTextVev.CATEGORY, "bulk", new QueryLimit(8))));
        assertEquals(first, indexed);
        assertTrue(vev.read(TENANT_8, tx -> tx.entities().find(LargeTextVev.INSTANCE.key(1))).isEmpty());
        var lookups = vev.read(TENANT_7, tx -> tx.entities().findMultiple(LargeTextVev.INSTANCE, Batch.copyOf(List.of(8, 1, 8, 2, 7, 6, 5, 4))));
        assertEquals(8, lookups.size());
        var updates = inputs.stream().map(row -> new LargeText(row.id(), 7, 0, null, "changed")).toList();
        var updated = vev.write(TENANT_7, tx -> tx.entities().updateMultiple(LargeTextVev.INSTANCE, Batch.copyOf(updates)));
        assertEquals(8, updated.size());
        assertTrue(updated.values().stream().allMatch(result -> result.entity().version() == 1));
        assertEquals(8, vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.isNull(LargeTextVev.CATEGORY, new QueryLimit(8)))).values().size());
    }

    @Test
    void oversizedPagesAndBatchesFailBeforeSqlWithoutPoisoningTheLexicalTransaction() {
        var count = new AtomicInteger();
        var authority = IntegrationModelVev.newTenantAuthority();
        var runtime = new PgVev<>(entityStatementCountingDataSource(database.applicationDataSource(), count),
                IntegrationModelVev.POSTGRES, authority);
        count.set(0);
        var large = LargeTextVev.INSTANCE;
        var keys = Batch.copyOf(java.util.stream.IntStream.rangeClosed(1, 9).boxed().toList());
        var rows = Batch.copyOf(keys.values().stream().map(key -> new LargeText(key, 7, 0, "batch", "body")).toList());
        var creations = Batch.copyOf(keys.values().stream().map(key -> new KotlinIdentityVev.New("row " + key, null)).toList());
        runtime.write(authority.scope(7), tx -> {
            assertThrows(IllegalArgumentException.class, () -> tx.entities().findMultiple(large, keys));
            assertThrows(IllegalArgumentException.class, () -> tx.entities().insertMultiple(large, rows));
            assertThrows(IllegalArgumentException.class, () -> tx.entities().updateMultiple(large, rows));
            assertThrows(IllegalArgumentException.class, () -> tx.entities().createMultiple(KotlinIdentityVev.INSTANCE, creations));
            QueryLimit excessive = new QueryLimit(9);
            for (var query : List.of(PgQueries.scanById(large, excessive), PgQueries.scanByIdAfter(large.key(1), excessive),
                    PgQueries.equal(LargeTextVev.CATEGORY, "batch", excessive),
                    PgQueries.equalAfter(LargeTextVev.CATEGORY, "batch", large.key(1), excessive),
                    PgQueries.isNull(LargeTextVev.CATEGORY, excessive), PgQueries.isNullAfter(LargeTextVev.CATEGORY, large.key(1), excessive))) {
                assertThrows(IllegalArgumentException.class, () -> tx.entities().many(query));
            }
            assertEquals(0, count.get());
            tx.entities().insert(large, rows.get(0));
            tx.entities().create(KotlinIdentityVev.INSTANCE, creations.get(0));
            assertEquals(2, count.get());
            return null;
        });
        assertEquals(rows.get(0), vev.read(TENANT_7, tx -> tx.entities().find(large.key(1))).orElseThrow());
    }

    @Test
    void checkCatalogAcceptsReviewedRowLocalExpressionsAndRejectsUnapprovedBehaviorWithoutExecution() throws SQLException {
        database.verifyCheckExpressionCatalog();
    }

    @Test
    void bootstrapRequiresExactDeclaredValidatedAndEnforcedCheckConstraints() throws SQLException {
        for (String variant : List.of("missing", "renamed", "weakened", "unvalidated", "unenforced", "noinherit", "extra", "unsafe")) {
            try {
                database.identityEntryCheck(variant);
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> runtime(database.applicationDataSource()), variant);
                assertTrue(failure.getMessage().contains("tenant isolation verification"),
                        variant + ": " + failure.getMessage());
            } finally {
                database.identityEntryCheck("valid");
            }
        }
        runtime(database.applicationDataSource());
    }

    @Test
    void checkViolationsRollBackEntireCreationBatchAndEarlierWritesEvenWhenCaught() {
        UUID earlier = id("check-earlier-write");
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> {
            tx.entities().insert(AccountVev.INSTANCE, account(earlier, 7, 0, "before-check@example.test", "1.0000"));
            assertThrows(IllegalStateException.class, () -> tx.entities().createMultiple(IdentityEntryVev.INSTANCE,
                    Batch.copyOf(List.of(new IdentityEntryVev.New("valid", null, null), new IdentityEntryVev.New("   ", null, null)))));
            assertThrows(IllegalStateException.class, () -> tx.entities().find(AccountVev.INSTANCE.key(earlier)));
            return null;
        }));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlier))).isEmpty());
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanById(IdentityEntryVev.INSTANCE, new QueryLimit(10)))).values().isEmpty());
        var created = vev.write(TENANT_7, tx -> tx.entities().create(IdentityEntryVev.INSTANCE, new IdentityEntryVev.New("valid", null, null)));
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> tx.entities().update(IdentityEntryVev.INSTANCE,
                new IdentityEntry(created.id(), 7, created.version(), "", null, null))));
        assertEquals(created, vev.read(TENANT_7, tx -> tx.entities().find(IdentityEntryVev.INSTANCE.key(created.id()))).orElseThrow());
    }

    @Test
    void binaryBoundsRequireExactColumnsLengthsNamesValidationAndEnforcementAtBootstrap() throws SQLException {
        for (String variant : List.of("missing", "renamed", "weakened", "tightened", "wrongcolumn",
                "unvalidated", "unenforced", "noinherit", "extra", "unsafe")) {
            try {
                database.binarySampleBound(variant);
                assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()), variant);
            } finally {
                database.binarySampleBound("valid");
            }
        }
        runtime(database.applicationDataSource());
    }

    @Test
    void identityConstraintFailureRollsBackBatchAndEarlierWritesButNotSequenceAllocation() {
        var committed = vev.write(TENANT_7, tx -> tx.entities().create(IdentityEntryVev.INSTANCE,
                new IdentityEntryVev.New("committed", "unique", null)));
        UUID earlierId = id("identity-earlier-write");
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> {
            tx.entities().insert(AccountVev.INSTANCE, account(earlierId, 7, 0, "earlier@example.test", "1.0000"));
            return tx.entities().createMultiple(IdentityEntryVev.INSTANCE, Batch.copyOf(List.of(
                    new IdentityEntryVev.New("first", null, null), new IdentityEntryVev.New("conflict", "unique", null))));
        }));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlierId))).isEmpty());
        var rows = vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanById(IdentityEntryVev.INSTANCE, new QueryLimit(10))));
        assertEquals(List.of(committed), rows.values());
        var next = vev.write(TENANT_7, tx -> tx.entities().create(IdentityEntryVev.INSTANCE,
                new IdentityEntryVev.New("after rollback", null, null)));
        assertTrue(next.id() > committed.id() + 1);
        var otherTenant = vev.write(TENANT_8, tx -> tx.entities().create(IdentityEntryVev.INSTANCE,
                new IdentityEntryVev.New("other tenant", "unique", null)));
        assertEquals(8, otherTenant.tenantId());
        UUID foreignId = id("identity-foreign-parent");
        insert(account(foreignId, 8, 0, "parent@example.test", "1.0000"), TENANT_8);
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7,
                tx -> tx.entities().create(IdentityEntryVev.INSTANCE, new IdentityEntryVev.New("cross tenant", null, foreignId))));
        assertEquals(2, vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.scanById(IdentityEntryVev.INSTANCE,
                new QueryLimit(10)))).values().size());
    }

    @Test
    void kotlinRecordDependencySupportsNullsBatchWritesTypedQueriesAndTenantIsolation() {
        var first = new KotlinEntry(10, 7, 0, "entry", null);
        var second = new KotlinEntry(20, 7, 0, "entry", "named");
        var inserted = vev.write(TENANT_7, tx -> tx.entities().insertMultiple(KotlinEntryVev.INSTANCE,
                Batch.copyOf(List.of(second, first))));
        assertEquals(List.of(second, first), inserted.values());
        assertEquals(first, vev.read(TENANT_7, tx -> tx.entities().find(KotlinEntryVev.INSTANCE.key(10L))).orElseThrow());
        assertTrue(vev.read(TENANT_8, tx -> tx.entities().find(KotlinEntryVev.INSTANCE.key(10L))).isEmpty());
        var changed = second.copy(20, 7, 0, "changed", null);
        var updated = vev.write(TENANT_7, tx -> tx.entities().updateMultiple(KotlinEntryVev.INSTANCE, Batch.one(changed)));
        assertEquals(changed.copy(20, 7, 1, "changed", null), updated.get(0).entity());
        var rows = vev.read(TENANT_7, tx -> tx.entities().many(PgQueries.equal(KotlinEntryVev.LABEL, "changed", new QueryLimit(10))));
        assertEquals(List.of(updated.get(0).entity()), rows.values());
        assertThrows(NullPointerException.class, () -> vev.write(TENANT_7,
                tx -> tx.entities().insert(KotlinEntryVev.INSTANCE, new KotlinEntry(30, 7, 0, null, null))));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(KotlinEntryVev.INSTANCE.key(30L))).isEmpty());
    }

    @Test
    void scalarReferencesEnforceTenantCompositeKeysAndRollbackEarlierWrites() {
        UUID targetId = id("reference-parent");
        insert(account(targetId, 8, 0, "parent@example.test", "1.0000"), TENANT_8);
        UUID earlierId = id("reference-earlier-write");
        var invalid = new WorkItem(id("reference-invalid"), 7, 0L, WorkState.OPEN, targetId);
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> {
            tx.entities().insert(AccountVev.INSTANCE, account(earlierId, 7, 0, "earlier@example.test", "2.0000"));
            tx.entities().insert(WorkItemVev.INSTANCE, invalid);
            return null;
        }));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlierId))).isEmpty());
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(WorkItemVev.INSTANCE.key(invalid.id()))).isEmpty());
        insert(account(targetId, 7, 0, "own-parent@example.test", "3.0000"), TENANT_7);
        assertEquals(invalid, vev.write(TENANT_7, tx -> tx.entities().insert(WorkItemVev.INSTANCE, invalid)));
        var saved = vev.read(TENANT_7, tx -> tx.entities().find(WorkItemVev.INSTANCE.key(invalid.id()))).orElseThrow();
        assertEquals(targetId, saved.accountId());
        assertTrue(vev.read(TENANT_8, tx -> tx.entities().find(WorkItemVev.INSTANCE.key(invalid.id()))).isEmpty());
    }

    @Test
    void invalidReferenceInABatchUpdatePreservesEveryOriginalSnapshot() {
        UUID targetId = id("batch-reference-parent");
        insert(account(targetId, 8, 0, "parent@example.test", "1.0000"), TENANT_8);
        var first = new WorkItem(id("reference-batch-first"), 7, 0L, WorkState.OPEN, null);
        var second = new WorkItem(id("reference-batch-second"), 7, 0L, WorkState.OPEN, null);
        vev.write(TENANT_7, tx -> tx.entities().insertMultiple(WorkItemVev.INSTANCE, Batch.copyOf(List.of(first, second))));
        var updates = Batch.copyOf(List.of(
                new WorkItem(first.id(), 7, 0L, WorkState.CLOSED, null),
                new WorkItem(second.id(), 7, 0L, WorkState.CLOSED, targetId)));
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7,
                tx -> tx.entities().updateMultiple(WorkItemVev.INSTANCE, updates)));
        assertEquals(first, vev.read(TENANT_7, tx -> tx.entities().find(WorkItemVev.INSTANCE.key(first.id()))).orElseThrow());
        assertEquals(second, vev.read(TENANT_7, tx -> tx.entities().find(WorkItemVev.INSTANCE.key(second.id()))).orElseThrow());
    }

    @Test
    void bootstrapRequiresExactForeignKeyEnforcementAndActions() throws SQLException {
        for (String variant : List.of("missing", "wrongColumn", "reversedColumns", "cascadeDelete", "cascadeUpdate",
                "deferrable", "unvalidated", "fullMatch", "disabledTrigger")) {
            try {
                database.setWorkItemReference(variant);
                assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()), variant);
            } finally {
                database.setWorkItemReference("valid");
            }
        }
        assertDoesNotThrow(() -> runtime(database.applicationDataSource()));
    }

    @Test
    void uniqueConstraintsAreTenantScopedAndKeepPostgresDistinctNullSemantics() {
        UUID parent = id("unique-parent");
        insert(account(parent, 7, 0, "first@example.test", "1.0000"), TENANT_7);
        insert(account(parent, 8, 0, "second@example.test", "1.0000"), TENANT_8);
        var first = new WorkItem(id("unique-first"), 7, 0L, WorkState.OPEN, parent);
        var otherTenant = new WorkItem(id("unique-other-tenant"), 8, 0L, WorkState.OPEN, parent);
        assertEquals(first, vev.write(TENANT_7, tx -> tx.entities().insert(WorkItemVev.INSTANCE, first)));
        assertEquals(otherTenant, vev.write(TENANT_8, tx -> tx.entities().insert(WorkItemVev.INSTANCE, otherTenant)));
        var distinctNulls = Batch.copyOf(List.of(
                new WorkItem(id("unique-null-state-a"), 7, 0L, null, parent),
                new WorkItem(id("unique-null-state-b"), 7, 0L, null, parent),
                new WorkItem(id("unique-null-parent-a"), 7, 0L, WorkState.OPEN, null),
                new WorkItem(id("unique-null-parent-b"), 7, 0L, WorkState.OPEN, null)));
        assertEquals(distinctNulls, vev.write(TENANT_7, tx -> tx.entities().insertMultiple(WorkItemVev.INSTANCE, distinctNulls)));
        var duplicate = new WorkItem(id("unique-duplicate"), 7, 0L, WorkState.OPEN, parent);
        UUID earlier = id("unique-earlier");
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx -> {
            tx.entities().insert(AccountVev.INSTANCE, account(earlier, 7, 0, "earlier@example.test", "2.0000"));
            tx.entities().insert(WorkItemVev.INSTANCE, duplicate);
            return null;
        }));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(earlier))).isEmpty());
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(WorkItemVev.INSTANCE.key(duplicate.id()))).isEmpty());
        assertEquals(first, vev.read(TENANT_7, tx -> tx.entities().find(WorkItemVev.INSTANCE.key(first.id()))).orElseThrow());
    }

    @Test
    void uniqueViolationsInBatchInsertsAndUpdatesRollbackEveryMember() {
        UUID parent = id("unique-batch-parent");
        insert(account(parent, 7, 0, "parent@example.test", "1.0000"), TENANT_7);
        var first = new WorkItem(id("unique-batch-a"), 7, 0L, WorkState.OPEN, parent);
        var second = new WorkItem(id("unique-batch-b"), 7, 0L, WorkState.OPEN, parent);
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7,
                tx -> tx.entities().insertMultiple(WorkItemVev.INSTANCE, Batch.copyOf(List.of(first, second)))));
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(WorkItemVev.INSTANCE.key(first.id()))).isEmpty());
        assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(WorkItemVev.INSTANCE.key(second.id()))).isEmpty());
        var secondOriginal = new WorkItem(second.id(), 7, 0L, WorkState.CLOSED, parent);
        vev.write(TENANT_7, tx -> tx.entities().insertMultiple(WorkItemVev.INSTANCE, Batch.copyOf(List.of(first, secondOriginal))));
        var firstChanged = new WorkItem(first.id(), 7, 0L, WorkState.CLOSED, parent);
        assertThrows(IllegalStateException.class, () -> vev.write(TENANT_7, tx ->
                tx.entities().updateMultiple(WorkItemVev.INSTANCE, Batch.copyOf(List.of(firstChanged, secondOriginal)))));
        assertEquals(first, vev.read(TENANT_7, tx -> tx.entities().find(WorkItemVev.INSTANCE.key(first.id()))).orElseThrow());
        assertEquals(secondOriginal, vev.read(TENANT_7, tx -> tx.entities().find(WorkItemVev.INSTANCE.key(second.id()))).orElseThrow());
    }

    @Test
    void bootstrapRequiresExactGeneratedUniqueConstraints() throws SQLException {
        for (String variant : List.of("missing", "wrongColumns", "wrongOrder", "global", "deferred", "nullsNotDistinct",
                "included", "nonUniqueIndex", "standaloneUniqueIndex")) {
            try {
                database.setWorkItemUniqueConstraint(variant);
                assertThrows(IllegalStateException.class, () -> runtime(database.applicationDataSource()), variant);
            } finally {
                database.setWorkItemUniqueConstraint("valid");
            }
        }
        assertDoesNotThrow(() -> runtime(database.applicationDataSource()));
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
    void displaySettingsAreVerifiedAtBootstrapCheckoutAndBeforeCommit() throws SQLException {
        for (String setting : List.of("DateStyle = 'ISO, DMY'", "IntervalStyle = 'iso_8601'")) {
            try (Connection connection = database.applicationDataSource().getConnection()) {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET " + setting);
                }
                DataSource changed = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                        (proxy, method, arguments) -> nonClosing(connection));
                assertThrows(IllegalStateException.class, () -> runtime(changed));
                var authority = IntegrationModelVev.newTenantAuthority();
                var runtime = new PgVev<>(firstCleanThenRetainedConnection(database.applicationDataSource(), connection),
                        IntegrationModelVev.POSTGRES, authority);
                var invocations = new AtomicInteger();
                assertThrows(IllegalStateException.class, () -> runtime.write(authority.scope(7), tx -> {
                    invocations.incrementAndGet();
                    return null;
                }));
                assertEquals(0, invocations.get());
            }
            try (Connection connection = database.applicationDataSource().getConnection()) {
                var authority = IntegrationModelVev.newTenantAuthority();
                var runtime = new PgVev<>(firstCleanThenRetainedConnection(database.applicationDataSource(), connection),
                        IntegrationModelVev.POSTGRES, authority);
                UUID key = id("display-" + setting);
                assertThrows(IllegalStateException.class, () -> runtime.write(authority.scope(7), tx -> {
                    tx.entities().insert(AccountVev.INSTANCE, account(key, 7, 0, "display@example.test", "1.0000"));
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET LOCAL " + setting);
                    } catch (SQLException failure) {
                        throw new AssertionError(failure);
                    }
                    return null;
                }));
                assertTrue(vev.read(TENANT_7, tx -> tx.entities().find(AccountVev.INSTANCE.key(key))).isEmpty());
            }
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

    private static DataSource binaryResultDataSource(DataSource source, AtomicReference<byte[]> borrowed, boolean corrupt) {
        return (DataSource) Proxy.newProxyInstance(VevPostgresIntegrationTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    Object result = invokeTarget(source, method, arguments);
                    if (!(result instanceof Connection connection)) return result;
                    return Proxy.newProxyInstance(VevPostgresIntegrationTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (connectionProxy, operation, parameters) -> {
                                Object value = invokeTarget(connection, operation, parameters);
                                if (!(value instanceof PreparedStatement statement)) return value;
                                return Proxy.newProxyInstance(VevPostgresIntegrationTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                        (statementProxy, call, inputs) -> {
                                            Object output = invokeTarget(statement, call, inputs);
                                            if (!(output instanceof java.sql.ResultSet rows)) return output;
                                            return Proxy.newProxyInstance(VevPostgresIntegrationTest.class.getClassLoader(), new Class<?>[]{java.sql.ResultSet.class},
                                                    (rowsProxy, access, positions) -> {
                                                        Object cell = invokeTarget(rows, access, positions);
                                                        if (access.getName().equals("getBytes") && cell instanceof byte[] bytes) {
                                                            borrowed.set(bytes);
                                                            if (corrupt && bytes.length > 0) bytes[0] ^= (byte) 0xff;
                                                        }
                                                        return cell;
                                                    });
                                        });
                            });
                });
    }

    private static Object invokeTarget(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static DataSource entityStatementCountingDataSource(DataSource source, AtomicInteger count) {
        return (DataSource) Proxy.newProxyInstance(VevPostgresIntegrationTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    Object result = invokeTarget(source, method, arguments);
                    if (!(result instanceof Connection connection)) return result;
                    return Proxy.newProxyInstance(VevPostgresIntegrationTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (connectionProxy, operation, parameters) -> {
                                if (operation.getName().equals("prepareStatement") && parameters[0] instanceof String sql
                                        && sql.contains("\"vev_it\".")) count.incrementAndGet();
                                return invokeTarget(connection, operation, parameters);
                            });
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
