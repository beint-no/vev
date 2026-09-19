package no.beint.vev;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Statement-local bindings; every created SQL array is freed before the statement closes. */
public final class Bindings implements AutoCloseable {
    private final Connection connection;
    private final PreparedStatement statement;
    private List<Array> arrays;

    Bindings(Connection connection, PreparedStatement statement) {
        this.connection = connection;
        this.statement = statement;
    }

    public PreparedStatement statement() { return statement; }

    public void array(int position, String elementType, Object[] values) throws SQLException {
        if (values == null) { statement.setNull(position, java.sql.Types.ARRAY); return; }
        Array array = connection.createArrayOf(elementType, values);
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
