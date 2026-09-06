package no.beint.vev.pg;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PgCheckedReaderTest {
    @Test
    void validatesCodecIdentityBeforeReadingAndChecksReturnedNullabilityAndBounds() throws SQLException {
        var required = new PgColumn("label", PgCodecs.STRING, false, PgColumn.Role.VALUE, 4, 0, 0);
        var optional = new PgColumn("label", PgCodecs.STRING, true, PgColumn.Role.VALUE, 4, 0, 0);
        var calls = new AtomicInteger();
        var row = row("🙂abcd", false, calls);
        assertThrows(IllegalArgumentException.class, () -> PgCodecs.TEXT.readChecked(row, 3, required));
        assertEquals(0, calls.get());
        assertThrows(IllegalArgumentException.class, () -> PgCodecs.STRING.readChecked(row, 3, required));
        assertEquals(2, calls.get());
        assertEquals("🙂abc", PgCodecs.STRING.readChecked(row("🙂abc", false, calls), 3, required));
        assertNull(PgCodecs.STRING.readChecked(row("stale driver value", true, calls), 3, optional));
        assertThrows(IllegalArgumentException.class, () -> PgCodecs.STRING.readChecked(row(null, true, calls), 3, required));
    }

    @Test
    void propagatesDriverFailuresWithoutAdditionalReadsOrCursorOperations() {
        var required = new PgColumn("label", PgCodecs.STRING, false, PgColumn.Role.VALUE, 4, 0, 0);
        ResultSet broken = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> {
                    assertEquals("getString", method.getName());
                    throw new SQLException("synthetic read failure", "08006");
                });
        SQLException failure = assertThrows(SQLException.class, () -> PgCodecs.STRING.readChecked(broken, 3, required));
        assertEquals("08006", failure.getSQLState());
    }

    private static ResultSet row(String value, boolean wasNull, AtomicInteger calls) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> {
                    calls.incrementAndGet();
                    return switch (method.getName()) {
                        case "getString" -> {
                            assertEquals(3, arguments[0]);
                            yield value;
                        }
                        case "wasNull" -> wasNull;
                        default -> throw new AssertionError("Unexpected JDBC cursor operation: " + method.getName());
                    };
                });
    }
}
