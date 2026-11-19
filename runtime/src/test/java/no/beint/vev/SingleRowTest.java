package no.beint.vev;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SingleRowTest {
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    static final class Fixture {
        int remaining;
        int fetchSize;
        int queryTimeout;
        int mapped;
        boolean resultClosed;
        boolean statementClosed;
        boolean failRead;
        final ResultSet result = proxy(ResultSet.class, (p, m, a) -> switch (m.getName()) {
            case "next" -> remaining-- > 0;
            case "close" -> { resultClosed = true; yield null; }
            default -> throw new UnsupportedOperationException(m.getName());
        });
        final PreparedStatement statement = proxy(PreparedStatement.class, (p, m, a) -> switch (m.getName()) {
            case "setFetchSize" -> { fetchSize = (int) a[0]; yield null; }
            case "setQueryTimeout" -> { queryTimeout = (int) a[0]; yield null; }
            case "executeQuery" -> result;
            case "close" -> { statementClosed = true; yield null; }
            default -> throw new UnsupportedOperationException(m.getName());
        });
        final Connection connection = proxy(Connection.class, (p, m, a) -> {
            if (m.getName().equals("prepareStatement")) return statement;
            throw new UnsupportedOperationException(m.getName());
        });
        final Session session;
        final Session.Row<Integer> row = r -> {
            mapped++;
            if (failRead) throw new SQLException("decoder failed", "22000");
            return 42;
        };
        Fixture(int rows) { this(rows, 0); }
        Fixture(int rows, int timeoutSeconds) { remaining = rows; session = new Session(connection, 7, timeoutSeconds); }
        void assertClosed() { assertTrue(resultClosed); assertTrue(statementClosed); }
    }

    @Test void emptyOptionalDoesNotMapAndKeepsDefaultFetchSize() throws Exception {
        var f = new Fixture(0);
        assertNull(f.session.optional("query", b -> {}, f.row));
        assertEquals(0, f.mapped);
        assertEquals(0, f.fetchSize);
        f.assertClosed();
        assertDoesNotThrow(f.session::check);
    }

    @Test void oneMapsExactlyOnceWithoutCursorSuspension() throws Exception {
        for (boolean required : new boolean[] {true, false}) {
            var f = new Fixture(1);
            assertEquals(42, required ? f.session.one("query", b -> {}, f.row) : f.session.optional("query", b -> {}, f.row));
            assertEquals(1, f.mapped);
            assertEquals(0, f.fetchSize);
            f.assertClosed();
        }
    }

    @Test void listLeavesFetchSizeAtZeroSoParallelQueriesRemainEnabled() throws Exception {
        var f = new Fixture(3);
        assertEquals(List.of(42, 42, 42), f.session.list("query", 10, b -> {}, f.row));
        assertEquals(0, f.fetchSize);
        f.assertClosed();
    }

    @Test void streamUsesExplicitCursorFetchSizeAndPoisonsOnConsumerFailure() throws Exception {
        var f = new Fixture(3, 15);
        List<Integer> seen = new ArrayList<>();
        f.session.stream("query", Session.DEFAULT_STREAM_FETCH_SIZE, b -> {}, f.row, seen::add);
        assertEquals(List.of(42, 42, 42), seen);
        assertEquals(Session.DEFAULT_STREAM_FETCH_SIZE, f.fetchSize);
        assertEquals(15, f.queryTimeout);
        f.assertClosed();

        var failing = new Fixture(3);
        assertThrows(IllegalStateException.class, () -> failing.session.stream("query", 64, b -> {}, failing.row, v -> {
            throw new IllegalStateException("consumer aborted");
        }));
        assertEquals(1, failing.mapped);
        failing.assertClosed();
        assertThrows(IllegalStateException.class, failing.session::check);
    }

    @Test void cardinalityFailuresPoisonScopeAndNeverMapAnExtraRow() {
        for (boolean required : new boolean[] {true, false}) {
            var f = new Fixture(2);
            assertThrows(IllegalStateException.class, () -> {
                if (required) f.session.one("query", b -> {}, f.row);
                else f.session.optional("query", b -> {}, f.row);
            });
            assertEquals(1, f.mapped);
            f.assertClosed();
            assertThrows(IllegalStateException.class, f.session::check);
        }
        var empty = new Fixture(0);
        assertThrows(IllegalStateException.class, () -> empty.session.one("query", b -> {}, empty.row));
        empty.assertClosed();
        assertThrows(IllegalStateException.class, empty.session::check);
    }

    @Test void aNullMappedRowCannotMasqueradeAsAnAbsentOptionalRow() {
        for (boolean required : new boolean[] {true, false}) {
            var f = new Fixture(1);
            assertThrows(NullPointerException.class, () -> {
                if (required) f.session.one("query", b -> {}, r -> null);
                else f.session.optional("query", b -> {}, r -> null);
            });
            f.assertClosed();
            assertThrows(IllegalStateException.class, f.session::check);
        }
    }

    @Test void decoderAndBindingFailuresPreserveSqlStateAndPoisonScope() {
        var decoder = new Fixture(1);
        decoder.failRead = true;
        assertEquals("22000", assertThrows(SQLException.class,
                () -> decoder.session.one("query", b -> {}, decoder.row)).getSQLState());
        decoder.assertClosed();
        assertThrows(IllegalStateException.class, decoder.session::check);
        var binding = new Fixture(1);
        assertEquals("22023", assertThrows(SQLException.class,
                () -> binding.session.optional("query", b -> { throw new SQLException("binding failed", "22023"); }, binding.row)).getSQLState());
        assertTrue(binding.statementClosed);
        assertEquals(0, binding.mapped);
        assertThrows(IllegalStateException.class, binding.session::check);
    }
}
