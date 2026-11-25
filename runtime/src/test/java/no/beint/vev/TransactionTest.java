package no.beint.vev;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {
    static class Fixture {
        final List<String> calls = new ArrayList<>();
        final List<String> statements = new ArrayList<>();
        boolean autoCommit = true;
        boolean readOnly;
        boolean failCommit;
        boolean failRollback;
        boolean failClose;
        int major = 19;
        int queryTimeout;
        final Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class}, (p, m, a) -> switch (m.getName()) {
            case "execute" -> { statements.add((String) a[0]); yield false; }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        final PreparedStatement prepared = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, (p, m, a) -> switch (m.getName()) {
            case "setQueryTimeout" -> { queryTimeout = (int) a[0]; yield null; }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        final Savepoint savepoint = (Savepoint) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Savepoint.class}, (p, m, a) -> switch (m.getName()) {
            case "getSavepointId" -> 1;
            case "getSavepointName" -> "sp_1";
            default -> throw new UnsupportedOperationException(m.getName());
        });
        final Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            calls.add(method.getName());
            return switch (method.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> { autoCommit = (boolean) args[0]; yield null; }
                case "isReadOnly" -> readOnly;
                case "createStatement" -> statement;
                case "prepareStatement" -> prepared;
                case "setSavepoint" -> savepoint;
                case "releaseSavepoint" -> null;
                case "getMetaData" -> Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DatabaseMetaData.class}, (p, m, a) -> switch (m.getName()) {
                    case "getDatabaseProductName" -> "PostgreSQL";
                    case "getDatabaseMajorVersion" -> major;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
                case "commit" -> { if (failCommit) throw new SQLException("commit outcome unknown", "08006"); yield null; }
                case "rollback" -> { if (failRollback) throw new SQLException("rollback failed"); yield null; }
                case "close" -> { if (failClose) throw new SQLException("close failed"); yield null; }
                default -> null;
            };
        });
        final DataSource source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, a) -> {
            if (m.getName().equals("getConnection")) return connection;
            throw new UnsupportedOperationException(m.getName());
        });
    }

    @Test void successCommitsAndClosesOnce() {
        Fixture f = new Fixture();
        assertEquals(42, new Db(f.source).write(s -> 42).intValue());
        assertEquals(1, f.calls.stream().filter("commit"::equals).count());
        assertEquals(1, f.calls.stream().filter("close"::equals).count());
        assertFalse(f.calls.contains("rollback"));
    }

    @Test void callbackFailureRollsBackAndPreservesOriginal() {
        Fixture f = new Fixture(); f.failRollback = true; f.failClose = true;
        var original = new IllegalArgumentException("original");
        var error = assertThrows(IllegalArgumentException.class, () -> new Db(f.source).write(s -> { throw original; }));
        assertSame(original, error);
        assertEquals(2, error.getSuppressed().length);
        assertFalse(f.calls.contains("commit"));
    }

    @Test void uncertainCommitIsNotRetriedAndTranslatesToVevSqlException() {
        Fixture f = new Fixture(); f.failCommit = true;
        VevSqlException error = assertThrows(VevSqlException.class, () -> new Db(f.source).write(s -> 1));
        assertEquals("08006", error.sqlState());
        assertTrue(error.isTransient());
        assertEquals(1, f.calls.stream().filter("commit"::equals).count());
        assertTrue(f.calls.contains("rollback"));
        assertTrue(f.calls.contains("close"));
    }

    @Test void closeFailureCannotTurnACommitIntoAReportedFailedWrite() {
        Fixture f = new Fixture(); f.failClose = true;
        assertEquals(42, new Db(f.source).write(s -> 42).intValue());
        assertFalse(f.calls.contains("rollback"));
    }

    @Test void unsupportedPostgresIsRejectedBeforeWork() {
        Fixture f = new Fixture(); f.major = 18;
        assertThrows(IllegalArgumentException.class, () -> new Db(f.source).read(s -> fail("work executed")));
        assertFalse(f.calls.contains("commit"));
        assertFalse(f.calls.contains("rollback"));
    }

    @Test void worksWithAutoCommitDisabledPoolsLikeHikari() {
        Fixture f = new Fixture(); f.autoCommit = false;
        assertEquals(7, new Db(f.source).write(1, s -> 7).intValue());
        assertFalse(f.calls.contains("setAutoCommit"));
        assertEquals(1, f.calls.stream().filter("commit"::equals).count());
    }

    @Test void verifiesPostgresMetadataOncePerDbInstance() {
        Fixture f = new Fixture();
        Db db = new Db(f.source);
        db.write(1, s -> 1);
        db.read(1, s -> 2);
        db.write(1, s -> 3);
        assertEquals(1, f.calls.stream().filter("getMetaData"::equals).count());
    }

    @Test void readUsesTransactionScopedReadOnlyWithoutMutatingSessionState() {
        Fixture f = new Fixture();
        new Db(f.source).read(7, s -> 1);
        assertFalse(f.calls.contains("setReadOnly"));
        assertEquals(List.of("SET TRANSACTION READ ONLY"), f.statements);
    }

    @Test void appliesConfiguredQueryTimeoutOnPreparedStatements() throws Exception {
        Fixture f = new Fixture();
        new Db(f.source, Db.DEFAULT_ISOLATION, Duration.ofSeconds(30)).read(7, s -> {
            try (var stmt = s.prepare("SELECT 1")) { return stmt; }
        });
        assertEquals(30, f.queryTimeout);

        Fixture borrowed = new Fixture(); borrowed.autoCommit = false;
        Session.borrowTenantRead(borrowed.connection, 7, Duration.ofSeconds(12), s -> {
            try (var stmt = s.prepare("SELECT 1")) { return stmt; }
        });
        assertEquals(12, borrowed.queryTimeout);
        assertFalse(borrowed.calls.contains("getMetaData"));
    }

    @Test void borrowedGlobalScopeAppliesItsTimeoutAndPreservesCallerOwnership() throws Exception {
        Fixture f = new Fixture(); f.autoCommit = false;
        Session.borrowRead(f.connection, Duration.ofMillis(1500), s -> {
            try (var stmt = s.prepare("SELECT 1")) { return stmt; }
        });
        assertEquals(2, f.queryTimeout, "A partial second rounds up instead of disabling the timeout");
        assertThrows(IllegalArgumentException.class, () -> Session.borrowRead(f.connection, Duration.ZERO, s -> 1));
        assertFalse(f.calls.contains("commit"));
        assertFalse(f.calls.contains("close"));
        assertFalse(f.calls.contains("rollback"));
    }

    @Test void isolationIsDeliberate() {
        Fixture f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> new Db(f.source, Connection.TRANSACTION_NONE));
        assertThrows(IllegalArgumentException.class, () -> new Db(f.source, Db.DEFAULT_ISOLATION, Duration.ofSeconds(-1)));
        assertDoesNotThrow(() -> new Db(f.source, Connection.TRANSACTION_SERIALIZABLE));
    }

    @Test void defaultIsolationDoesNotIssueRedundantDatabaseCommands() {
        Fixture f = new Fixture();
        new Db(f.source).read(7, s -> 1);
        assertFalse(f.calls.contains("setTransactionIsolation"));
        assertEquals(List.of("SET TRANSACTION READ ONLY"), f.statements);
        Fixture explicit = new Fixture();
        new Db(explicit.source, Connection.TRANSACTION_SERIALIZABLE).read(7, s -> 1);
        assertFalse(explicit.calls.contains("setTransactionIsolation"));
        assertEquals(List.of("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE, READ ONLY"), explicit.statements);
    }

    @Test void readNewOpensIndependentReadOnlyTransactionAndRejectsWrites() {
        Fixture f = new Fixture();
        List<String> observed = new ArrayList<>();
        Db db = Db.builder(f.source)
                .tenantSupplier(() -> 7)
                .transactionListener(new Db.TransactionListener() {
                    @Override public Object onBegin(boolean readOnly, int tenantId) {
                        observed.add("begin:" + readOnly + ":" + tenantId);
                        return "tok";
                    }
                    @Override public void onEnd(Object token, boolean committed, Throwable failure) {}
                })
                .build();
        db.write(7, outer -> {
            assertTrue(db.inWriteTransaction());
            db.requireWriteTransaction();
            return db.tenantReadNew(inner -> {
                assertTrue(db.inTransaction());
                assertFalse(db.inWriteTransaction());
                assertEquals(7, inner.tenantId());
                assertThrows(VevSessionException.class, db::requireWriteTransaction);
                assertThrows(VevSessionException.class, () -> db.tenantWrite(w -> 1));
                return "ok";
            });
        });
        assertEquals(List.of("begin:false:7", "begin:true:7"), observed);
        assertEquals(List.of("SET TRANSACTION READ ONLY"), f.statements);
    }

    @Test void readRepeatableAndWriteSerializableRejectElevationAndDoNotLeakAcrossWriteNew() {
        Fixture f = new Fixture();
        Db db = new Db(f.source);
        // Joining at same or lower isolation succeeds; raising isolation fails.
        db.readRepeatable(7, outer -> {
            assertEquals(7, db.readRepeatable(7, TenantReadScope::tenantId).intValue());
            assertEquals(7, db.read(7, TenantReadScope::tenantId).intValue());
            // Opening a fresh transaction inside readRepeatable does NOT inherit REPEATABLE READ.
            db.writeNew(7, fresh -> {
                assertThrows(VevSessionException.class, () -> db.readRepeatable(7, inner -> 1));
                return null;
            });
            return null;
        });
        assertThrows(VevSessionException.class, () -> db.read(7, outer -> db.readRepeatable(7, inner -> 1)));
        assertThrows(VevSessionException.class, () -> db.write(7, outer -> db.writeSerializable(7, inner -> 1)));
        assertThrows(VevSessionException.class, () -> db.readRepeatable(7, outer -> db.writeSerializable(7, inner -> 1)));
        assertEquals(List.of("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY", "SET TRANSACTION READ ONLY", "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY"), f.statements);
    }

    @Test void withTimeoutAppliesAndRestoresStatementTimeoutAcrossNesting() {
        Fixture f = new Fixture();
        Db db = Db.builder(f.source).queryTimeout(Duration.ofSeconds(30)).build();
        db.write(7, outer -> {
            try (var s = outer.prepare("SELECT 1")) { assertNotNull(s); assertEquals(30, f.queryTimeout); }
            db.withTimeout(Duration.ofSeconds(5)).read(7, inner -> {
                try (var s = inner.prepare("SELECT 1")) { assertNotNull(s); assertEquals(5, f.queryTimeout); }
                // Normal nested read without explicit withTimeout inherits the 5s override.
                db.read(7, inherited -> {
                    try (var s = inherited.prepare("SELECT 1")) { assertNotNull(s); assertEquals(5, f.queryTimeout); }
                    return null;
                });
                return null;
            });
            // Restored back to 30s after nested withTimeout exits.
            try (var s = outer.prepare("SELECT 1")) { assertNotNull(s); assertEquals(30, f.queryTimeout); }
            return null;
        });
        assertEquals(List.of(
                "SET LOCAL statement_timeout = 30000",
                "SET LOCAL statement_timeout = 5000",
                "SET LOCAL statement_timeout = 30000"), f.statements);
    }

    @Test void afterCommitIsolatesFailuresAndBeforeCommitPreservesTenant() {
        Fixture f = new Fixture();
        List<String> events = new ArrayList<>();
        Db db = new Db(f.source);
        RuntimeException failure = assertThrows(IllegalStateException.class, () -> db.write(global -> {
            db.write(42, tenantTx -> {
                tenantTx.beforeCommit(() -> events.add("before:" + db.currentTenantId()));
                db.beforeCommit(() -> events.add("dbBefore:" + db.currentTenantId()));
                return null;
            });
            global.afterCommit(() -> {
                events.add("after1");
                throw new IllegalStateException("boom1");
            });
            global.afterCommit(() -> {
                events.add("after2");
                throw new IllegalArgumentException("boom2");
            });
            global.afterCommit(() -> events.add("after3"));
            return null;
        }));
        assertEquals("boom1", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("boom2", failure.getSuppressed()[0].getMessage());
        assertEquals(List.of("before:42", "dbBefore:42", "after1", "after2", "after3"), events);
    }

    @Test void savepointRollsBackFailuresAndDiscardsNestedCallbacksWithoutPoisoningOuterTx() {
        Fixture f = new Fixture();
        List<String> events = new ArrayList<>();
        Db db = new Db(f.source);
        assertThrows(VevSessionException.class, () -> db.savepoint(tx -> 1));
        assertThrows(VevSessionException.class, () -> db.read(tx -> db.savepoint(w -> 1)));
        f.calls.clear();

        db.write(7, outer -> {
            outer.afterCommit(() -> events.add("outer-after"));
            VevSqlException caught = assertThrows(VevSqlException.class, () -> db.tenantSavepoint(sp -> {
                sp.afterCommit(() -> events.add("rolled-back-after"));
                sp.beforeCommit(() -> events.add("rolled-back-before"));
                throw new SQLException("duplicate key", "23505");
            }));
            assertTrue(caught.isUniqueViolation());
            // Outer transaction remains healthy after savepoint rollback.
            assertEquals(7, outer.tenantId());
            db.tenantSavepoint(sp -> {
                sp.afterCommit(() -> events.add("committed-sp-after"));
                return null;
            });
            return null;
        });
        assertEquals(List.of("outer-after", "committed-sp-after"), events);
        assertEquals(2, f.calls.stream().filter("setSavepoint"::equals).count());
        assertEquals(2, f.calls.stream().filter("releaseSavepoint"::equals).count());
        assertEquals(1, f.calls.stream().filter("rollback"::equals).count());
        assertEquals(1, f.calls.stream().filter("commit"::equals).count());
    }

    @Test void reentrantCallsJoinSameConnectionAndRejectReadToWriteEscalation() {
        Fixture f = new Fixture();
        Db db = new Db(f.source);
        AtomicReference<ReadScope> escapedChild = new AtomicReference<>();
        String value = db.write(7, outer -> {
            assertTrue(db.inTransaction());
            assertTrue(db.inWriteTransaction());
            assertEquals(7, outer.tenantId());
            return db.read(7, inner -> {
                escapedChild.set(inner);
                assertSame(outer.connection(), inner.connection());
                assertEquals(7, inner.tenantId());
                return db.read(global -> {
                    assertSame(outer.connection(), global.connection());
                    return "ok";
                });
            });
        });
        assertEquals("ok", value);
        assertEquals(1, f.calls.stream().filter("commit"::equals).count());
        assertEquals(1, f.calls.stream().filter("close"::equals).count());
        assertThrows(VevSessionException.class, () -> escapedChild.get().connection());

        assertThrows(VevSessionException.class, () -> db.read(7, outer -> db.write(7, inner -> 1)));
        assertThrows(VevSessionException.class, () -> db.read(outer -> db.write(inner -> 1)));
    }

    @Test void catchingDomainExceptionInNestedCallDoesNotPoisonEnclosingTransaction() {
        Fixture f = new Fixture();
        Db db = new Db(f.source);
        String result = db.write(7, outer -> {
            try {
                db.read(7, inner -> { throw new IllegalArgumentException("not found"); });
            } catch (IllegalArgumentException ignored) {}
            return "committed";
        });
        assertEquals("committed", result);
        assertEquals(1, f.calls.stream().filter("commit"::equals).count());
        assertFalse(f.calls.contains("rollback"));
    }

    @Test void tenantSwitchingInsideActiveTenantTransactionIsRejectedAndGlobalCanEnterTenant() {
        Fixture f = new Fixture();
        Db db = new Db(f.source);
        db.write(7, outer -> {
            assertThrows(VevSessionException.class, () -> db.read(8, inner -> 1));
            assertThrows(VevSessionException.class, () -> db.write(8, inner -> 1));
            assertEquals(7, outer.tenantId());
            return null;
        });
        // A global transaction may enter distinct tenants sequentially without leaking between them.
        db.write(global -> {
            assertEquals(7, db.read(7, ReadScope -> ReadScope.tenantId()).intValue());
            assertEquals(8, db.read(8, ReadScope -> ReadScope.tenantId()).intValue());
            return null;
        });
    }

    @Test void writeNewOpensIndependentConnectionAndWriteIsolatedRejectsNesting() {
        Fixture f = new Fixture();
        Db db = new Db(f.source);
        db.write(7, outer -> {
            assertThrows(VevSessionException.class, () -> db.writeIsolated(7, inner -> 1));
            assertThrows(VevSessionException.class, () -> db.writeIsolated(inner -> 1));
            return db.writeNew(8, inner -> {
                assertEquals(8, inner.tenantId());
                return "independent";
            });
        });
        assertEquals(2, f.calls.stream().filter("commit"::equals).count());
        assertEquals(2, f.calls.stream().filter("close"::equals).count());
    }

    @Test void beforeCommitAfterCommitAndAfterCompletionFollowExactLifecycleOrder() {
        Fixture f = new Fixture();
        List<String> events = new ArrayList<>();
        AtomicInteger closedBeforeAfterCommit = new AtomicInteger();
        Db db = Db.builder(f.source)
                .callbackDecorator(r -> () -> { events.add("decorated"); r.run(); })
                .build();
        db.write(tx -> {
            events.add("body");
            tx.beforeCommit(() -> {
                events.add("before1");
                db.beforeCommit(() -> events.add("before2"));
            });
            tx.afterCommit(() -> {
                assertFalse(db.inTransaction());
                closedBeforeAfterCommit.set((int) f.calls.stream().filter("close"::equals).count());
                events.add("afterCommit");
            });
            tx.afterCompletion(() -> {
                assertFalse(db.inTransaction());
                events.add("afterCompletion");
            });
            return null;
        });
        assertEquals(1, closedBeforeAfterCommit.get(), "Connection must close before afterCommit runs");
        assertEquals(List.of("body", "before1", "before2", "decorated", "afterCommit", "decorated", "afterCompletion"), events);

        // On rollback, beforeCommit and afterCommit do not run, while afterCompletion does.
        events.clear();
        assertThrows(IllegalStateException.class, () -> db.write(tx -> {
            tx.beforeCommit(() -> events.add("before"));
            tx.afterCommit(() -> events.add("afterCommit"));
            tx.afterCompletion(() -> events.add("afterCompletion"));
            throw new IllegalStateException("abort");
        }));
        assertEquals(List.of("decorated", "afterCompletion"), events);

        // Outside a transaction, afterCommit/afterCompletion run immediately; beforeCommit fails.
        events.clear();
        assertThrows(VevSessionException.class, () -> db.beforeCommit(() -> {}));
        db.afterCommit(() -> events.add("immediate"));
        assertEquals(List.of("decorated", "immediate"), events);
    }

    @Test void tenantSupplierAndTransactionListenerWorkEndToEnd() {
        Fixture f = new Fixture();
        AtomicInteger currentTenant = new AtomicInteger(42);
        List<String> observed = new ArrayList<>();
        Db db = Db.builder(f.source)
                .tenantSupplier(currentTenant::get)
                .transactionListener(new Db.TransactionListener() {
                    @Override public Object onBegin(boolean readOnly, int tenantId) {
                        observed.add("begin:" + readOnly + ":" + tenantId);
                        return "tok";
                    }
                    @Override public void onEnd(Object token, boolean committed, Throwable failure) {
                        observed.add("end:" + token + ":" + committed + ":" + (failure == null));
                    }
                })
                .build();
        int nested = db.write(99, outer -> {
            assertEquals(99, db.currentTenantId());
            assertEquals(99, db.currentTenantIdOrNull());
            return db.tenantRead(TenantReadScope::tenantId);
        });
        assertEquals(99, nested, "tenantRead inside explicit tenant 99 inherits 99 instead of ambient 42");
        assertEquals(42, db.currentTenantId());
        assertEquals(42, db.currentTenantIdOrNull());
        assertEquals(42, db.tenantRead(TenantReadScope::tenantId).intValue());
        assertEquals(List.of("begin:false:99", "end:tok:true:true", "begin:true:42", "end:tok:true:true"), observed);

        AtomicReference<Integer> nullableTenant = new AtomicReference<>(null);
        Db nullableDb = Db.builder(f.source).tenantSupplier(nullableTenant::get).build();
        assertNull(nullableDb.currentTenantIdOrNull());
        assertThrows(VevSessionException.class, nullableDb::currentTenantId);
        assertThrows(VevSessionException.class, () -> nullableDb.tenantRead(TenantReadScope::tenantId));
        assertEquals(Integer.valueOf(11), nullableDb.read(11, tx -> nullableDb.currentTenantIdOrNull()));
        nullableTenant.set(5);
        assertEquals(5, nullableDb.currentTenantId());
        assertEquals(5, nullableDb.currentTenantIdOrNull());
    }

    @Test void transactionBackendBridgeDelegatesLifecycleAndEnforcesReadOnly() {
        Fixture f = new Fixture();
        f.autoCommit = false;
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean readOnly = new AtomicBoolean(true);
        List<String> hooks = new ArrayList<>();
        Db.TransactionBackend backend = new Db.TransactionBackend() {
            @Override public <R> R execute(boolean ro, boolean requiresNew, BackendWork<R> work) throws SQLException {
                return work.run(f.connection, Duration.ofSeconds(15));
            }
            @Override public boolean isTransactionActive() { return active.get(); }
            @Override public boolean isCurrentTransactionReadOnly() { return readOnly.get(); }
            @Override public void beforeCommit(Runnable action) { hooks.add("before"); action.run(); }
            @Override public void afterCommit(Runnable action) { hooks.add("afterCommit"); action.run(); }
            @Override public void afterCompletion(Runnable action) { hooks.add("afterCompletion"); action.run(); }
        };
        Db db = Db.builder(backend).tenantSupplier(() -> 7).build();
        assertTrue(db.inTransaction());
        assertFalse(db.inWriteTransaction());
        assertThrows(VevSessionException.class, () -> db.tenantWrite(tx -> 1));
        db.tenantRead(tx -> {
            assertEquals(7, tx.tenantId());
            tx.afterCommit(() -> hooks.add("ran"));
            try (var stmt = tx.prepare("SELECT 1")) { return stmt; }
        });
        assertEquals(15, f.queryTimeout);
        assertEquals(List.of("afterCommit", "ran"), hooks);
    }

    @Test void vevSqlExceptionHelpersClassifyPostgresSqlStates() {
        var unique = new VevSqlException(new SQLException("dup", "23505"));
        assertTrue(unique.isUniqueViolation());
        assertTrue(unique.isIntegrityViolation());
        assertFalse(unique.isTransient());
        assertTrue(VevSqlException.isUniqueViolation(new RuntimeException(unique)));

        var fk = new VevSqlException(new SQLException("fk", "23503"));
        assertTrue(fk.isForeignKeyViolation());
        assertTrue(VevSqlException.isForeignKeyViolation(fk));

        var deadlock = new VevSqlException(new SQLException("deadlock", "40P01"));
        assertTrue(deadlock.isDeadlock());
        assertTrue(deadlock.isTransient());
        assertTrue(VevSqlException.isTransient(deadlock));
    }

    @Test void withLockTimeoutComposesWithWithTimeoutAndRestoresPreviousSetting() {
        Fixture f = new Fixture();
        Db db = Db.builder(f.source)
                .queryTimeout(Duration.ofSeconds(30))
                .lockTimeout(Duration.ofSeconds(2))
                .build();
        assertThrows(IllegalArgumentException.class, () -> db.withLockTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> db.withLockTimeout(Duration.ofMillis(-1)));
        db.withTimeout(Duration.ofSeconds(10)).withLockTimeout(Duration.ofMillis(500)).write(outer -> {
            assertTrue(f.statements.stream().anyMatch(s -> s.contains("SET LOCAL statement_timeout = 10000")
                    && s.contains("SET LOCAL lock_timeout = 500")), f.statements::toString);
            db.withLockTimeout(null).write(inner -> {
                assertTrue(f.statements.stream().anyMatch(s -> s.equals("SET LOCAL lock_timeout = 0")), f.statements::toString);
                return null;
            });
            assertTrue(f.statements.stream().anyMatch(s -> s.equals("SET LOCAL lock_timeout = 500")), f.statements::toString);
            return null;
        });
    }
}
