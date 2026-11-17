package no.beint.vev;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SingleRowTest {
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    static final class Fixture {
        int remaining;
        int fetchSize;
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
            case "executeQuery" -> result;
            case "close" -> { statementClosed = true; yield null; }
            default -> throw new UnsupportedOperationException(m.getName());
        });
        final Connection connection = proxy(Connection.class, (p, m, a) -> {
            if (m.getName().equals("prepareStatement")) return statement;
            throw new UnsupportedOperationException(m.getName());
        });
        final Session session = new Session(connection, 7);
        final Session.Row<Integer> row = r -> {
            mapped++;
            if (failRead) throw new SQLException("decoder failed", "22000");
            return 42;
        };
        Fixture(int rows) { remaining = rows; }
        void assertClosed() { assertTrue(resultClosed); assertTrue(statementClosed); }
    }

    @Test void emptyOptionalDoesNotMapAndClosesResources() throws Exception {
        var f = new Fixture(0);
        assertNull(f.session.optional("query", b -> {}, f.row));
        assertEquals(0, f.mapped);
        assertEquals(2, f.fetchSize);
        f.assertClosed();
        assertDoesNotThrow(f.session::check);
    }

    @Test void oneMapsExactlyOnceAndClosesResources() throws Exception {
        for (boolean required : new boolean[] {true, false}) {
            var f = new Fixture(1);
            assertEquals(42, required ? f.session.one("query", b -> {}, f.row) : f.session.optional("query", b -> {}, f.row));
            assertEquals(1, f.mapped);
            assertEquals(2, f.fetchSize);
            f.assertClosed();
        }
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
