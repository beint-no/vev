package no.beint.vev;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
        int major = 18;
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
        final Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            calls.add(method.getName());
            return switch (method.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> { autoCommit = (boolean) args[0]; yield null; }
                case "isReadOnly" -> readOnly;
                case "createStatement" -> statement;
                case "prepareStatement" -> prepared;
                case "getMetaData" -> Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DatabaseMetaData.class}, (p, m, a) -> switch (m.getName()) {
                    case "getDatabaseProductName" -> "PostgreSQL";
                    case "getDatabaseMajorVersion" -> major;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
                case "commit" -> { if (failCommit) throw new SQLException("commit outcome unknown"); yield null; }
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
    @Test void successCommitsAndClosesOnce() throws Exception {
        Fixture f = new Fixture();
        assertEquals(42, new Vev(f.source).<Integer>writeGlobal(s -> 42).intValue());
        assertEquals(1, f.calls.stream().filter("commit"::equals).count());
        assertEquals(1, f.calls.stream().filter("close"::equals).count());
        assertFalse(f.calls.contains("rollback"));
    }
    @Test void callbackFailureRollsBackAndPreservesOriginal() {
        Fixture f = new Fixture(); f.failRollback = true; f.failClose = true;
        var original = new IllegalArgumentException("original");
        var error = assertThrows(IllegalArgumentException.class, () -> new Vev(f.source).writeGlobal(s -> { throw original; }));
        assertSame(original, error);
        assertEquals(2, error.getSuppressed().length);
        assertFalse(f.calls.contains("commit"));
    }
    @Test void uncertainCommitIsNotRetried() {
        Fixture f = new Fixture(); f.failCommit = true;
        assertThrows(SQLException.class, () -> new Vev(f.source).writeGlobal(s -> 1));
        assertEquals(1, f.calls.stream().filter("commit"::equals).count());
        assertTrue(f.calls.contains("rollback"));
        assertTrue(f.calls.contains("close"));
    }
    @Test void closeFailureCannotTurnACommitIntoAReportedFailedWrite() throws Exception {
        Fixture f = new Fixture(); f.failClose = true;
        assertEquals(42, new Vev(f.source).<Integer>writeGlobal(s -> 42).intValue());
        assertFalse(f.calls.contains("rollback"));
    }
    @Test void unsupportedPostgresIsRejectedBeforeWork() {
        Fixture f = new Fixture(); f.major = 17;
        assertThrows(IllegalArgumentException.class, () -> new Vev(f.source).readGlobal(s -> fail("work executed")));
        assertFalse(f.calls.contains("commit"));
        assertFalse(f.calls.contains("rollback"));
    }
    @Test void worksWithAutoCommitDisabledPoolsLikeHikari() throws Exception {
        Fixture f = new Fixture(); f.autoCommit = false;
        assertEquals(7, new Vev(f.source).<Integer>write(1, s -> 7).intValue());
        assertFalse(f.calls.contains("setAutoCommit"));
        assertEquals(1, f.calls.stream().filter("commit"::equals).count());
    }
    @Test void verifiesPostgresMetadataOncePerVevInstance() throws Exception {
        Fixture f = new Fixture();
        Vev vev = new Vev(f.source);
        vev.write(1, s -> 1);
        vev.read(1, s -> 2);
        vev.write(1, s -> 3);
        assertEquals(1, f.calls.stream().filter("getMetaData"::equals).count());
    }
    @Test void readUsesTransactionScopedReadOnlyWithoutMutatingSessionState() throws Exception {
        Fixture f = new Fixture();
        new Vev(f.source).read(7, s -> 1);
        assertFalse(f.calls.contains("setReadOnly"));
        assertEquals(List.of("SET TRANSACTION READ ONLY"), f.statements);
    }
    @Test void appliesConfiguredQueryTimeoutOnPreparedStatements() throws Exception {
        Fixture f = new Fixture();
        new Vev(f.source, Vev.DEFAULT_ISOLATION, 30).read(7, s -> {
            try (var stmt = s.prepare("SELECT 1")) { return stmt; }
        });
        assertEquals(30, f.queryTimeout);

        Fixture borrowed = new Fixture(); borrowed.autoCommit = false;
        Session.borrowTenant(borrowed.connection, 7, 12, s -> {
            try (var stmt = s.prepare("SELECT 1")) { return stmt; }
        });
        assertEquals(12, borrowed.queryTimeout);
        assertFalse(borrowed.calls.contains("getMetaData"));
    }
    @Test void borrowedScopeDoesNotCommitCloseOrRollback() throws Exception {
        Fixture f = new Fixture(); f.autoCommit = false;
        assertEquals(7, Session.<Integer>borrow(f.connection, s -> 7).intValue());
        assertFalse(f.calls.contains("commit"));
        assertFalse(f.calls.contains("close"));
        assertFalse(f.calls.contains("rollback"));
    }
    @Test void isolationIsDeliberate() {
        Fixture f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> new Vev(f.source, Connection.TRANSACTION_NONE));
        assertThrows(IllegalArgumentException.class, () -> new Vev(f.source, Vev.DEFAULT_ISOLATION, -1));
        assertDoesNotThrow(() -> new Vev(f.source, Connection.TRANSACTION_SERIALIZABLE));
    }
    @Test void defaultIsolationDoesNotIssueRedundantDatabaseCommands() throws Exception {
        Fixture f = new Fixture();
        new Vev(f.source).read(7, s -> 1);
        assertFalse(f.calls.contains("setTransactionIsolation"));
        Fixture explicit = new Fixture();
        new Vev(explicit.source, Connection.TRANSACTION_SERIALIZABLE).read(7, s -> 1);
        assertEquals(1, explicit.calls.stream().filter("setTransactionIsolation"::equals).count());
    }
}
