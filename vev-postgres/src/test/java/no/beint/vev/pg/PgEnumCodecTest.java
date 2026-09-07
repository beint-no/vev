package no.beint.vev.pg;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PgEnumCodecTest {
    private enum State {
        OPEN {
            @Override
            public String toString() {
                return "presentation only";
            }
        },
        CLOSED
    }

    @Test
    void snapshotsConstantsAndPreservesNamesForConstantSpecificSubclasses() throws SQLException {
        State[] constants = State.values();
        PgCodec<State> codec = PgCodecs.enumNames(State.class, constants);
        constants[0] = State.CLOSED;
        assertSame(State.OPEN, codec.read(row("OPEN"), 1));
        assertNull(codec.read(row(null), 1));
        assertEquals("OPEN", codec.arrayElement(State.OPEN));
        assertThrows(SQLException.class, () -> codec.read(row("presentation only"), 1));
        assertThrows(SQLException.class, () -> codec.read(row("REMOVED"), 1));
        assertThrows(IllegalArgumentException.class, () -> codec.arrayElement("OPEN"));
        new PgColumn("state", codec, false, PgColumn.Role.VALUE, 6, 0, 0).validateValue(State.OPEN);
        assertThrows(IllegalArgumentException.class, () ->
                new PgColumn("state", codec, false, PgColumn.Role.VALUE, 3, 0, 0).validateValue(State.OPEN));
    }

    @Test
    void rejectsInvalidConstantSets() {
        assertThrows(IllegalArgumentException.class, () -> PgCodecs.enumNames(State.class, new State[0]));
        assertThrows(IllegalArgumentException.class, () -> PgCodecs.enumNames(State.class, new State[1_025]));
        assertThrows(IllegalArgumentException.class, () ->
                PgCodecs.enumNames(State.class, new State[]{State.OPEN, State.OPEN}));
        assertThrows(NullPointerException.class, () -> PgCodecs.enumNames(State.class, new State[]{null}));
    }

    private static ResultSet row(String value) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getString" -> value;
                    case "wasNull" -> value == null;
                    default -> throw new AssertionError("Unexpected JDBC operation: " + method.getName());
                });
    }
}
