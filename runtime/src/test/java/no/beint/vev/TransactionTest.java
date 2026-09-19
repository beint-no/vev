package no.beint.vev;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {
    static class Fixture {
        final List<String> calls = new ArrayList<>();
        boolean autoCommit = true;
        boolean failCommit;
        boolean failRollback;
        boolean failClose;
        int major = 18;
        final Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            calls.add(method.getName());
            return switch (method.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> { autoCommit = (boolean) args[0]; yield null; }
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
        assertEquals(42, new Vev(f.source).<Integer>write(s -> 42).intValue());
        assertEquals(1, f.calls.stream().filter("commit"::equals).count());
        assertEquals(1, f.calls.stream().filter("close"::equals).count());
        assertFalse(f.calls.contains("rollback"));
    }
    @Test void callbackFailureRollsBackAndPreservesOriginal() {
        Fixture f = new Fixture(); f.failRollback = true; f.failClose = true;
        var original = new IllegalArgumentException("original");
        var error = assertThrows(IllegalArgumentException.class, () -> new Vev(f.source).write(s -> { throw original; }));
        assertSame(original, error);
        assertEquals(2, error.getSuppressed().length);
        assertFalse(f.calls.contains("commit"));
    }
    @Test void uncertainCommitIsNotRetried() {
        Fixture f = new Fixture(); f.failCommit = true;
        assertThrows(SQLException.class, () -> new Vev(f.source).write(s -> 1));
        assertEquals(1, f.calls.stream().filter("commit"::equals).count());
        assertTrue(f.calls.contains("rollback"));
        assertTrue(f.calls.contains("close"));
    }
    @Test void closeFailureCannotTurnACommitIntoAReportedFailedWrite() throws Exception {
        Fixture f = new Fixture(); f.failClose = true;
        assertEquals(42, new Vev(f.source).<Integer>write(s -> 42).intValue());
        assertFalse(f.calls.contains("rollback"));
    }
    @Test void unsupportedPostgresIsRejectedBeforeWork() {
        Fixture f = new Fixture(); f.major = 17;
        assertThrows(IllegalArgumentException.class, () -> new Vev(f.source).read(s -> fail("work executed")));
        assertFalse(f.calls.contains("commit"));
    }
    @Test void doesNotRollbackAnUnownedTransaction() {
        Fixture f = new Fixture(); f.autoCommit = false;
        assertThrows(IllegalStateException.class, () -> new Vev(f.source).read(s -> 1));
        assertFalse(f.calls.contains("rollback"));
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
        assertDoesNotThrow(() -> new Vev(f.source, Connection.TRANSACTION_SERIALIZABLE));
    }
}
