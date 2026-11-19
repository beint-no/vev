package no.beint.vev;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Statement-local bindings; every created SQL array is freed before the statement closes. */
public final class Bindings implements AutoCloseable {
    private final Connection connection;
    private final PreparedStatement statement;
    private List<Array> arrays;

    public Bindings(Connection connection, PreparedStatement statement) {
        this.connection = connection;
        this.statement = statement;
    }

    public PreparedStatement statement() { return statement; }

    public void array(int position, String elementType, Object[] values) throws SQLException {
        if (values == null) { statement.setNull(position, java.sql.Types.ARRAY); return; }
        Object[] adapted = values;
        if (elementType.equals("timestamptz")) {
            OffsetDateTime[] converted = new OffsetDateTime[values.length];
            for (int i = 0; i < values.length; i++) {
                Object v = values[i];
                if (v instanceof Instant instant) converted[i] = instant.atOffset(ZoneOffset.UTC);
                else if (v instanceof OffsetDateTime odt) converted[i] = odt;
                else if (v != null) throw new java.sql.SQLDataException("Expected Instant for timestamptz[] element");
            }
            adapted = converted;
        }
        Array array = connection.createArrayOf(elementType, adapted);
        if (arrays == null) arrays = new ArrayList<>();
        arrays.add(array);
        statement.setArray(position, array);
    }

    @Override public void close() throws SQLException {
        if (arrays == null) return;
        SQLException failure = null;
        for (Array array : arrays) {
            try { array.free(); }
            catch (SQLException exception) {
                if (failure == null) failure = exception; else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }
}
