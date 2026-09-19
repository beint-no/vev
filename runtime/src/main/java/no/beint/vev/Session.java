package no.beint.vev;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** A thread-confined, lexical execution scope. Generated queries own their JDBC resources. */
public final class Session {
    private final Connection connection;
    private final Thread owner = Thread.currentThread();
    private boolean active = true;
    private boolean failed;

    Session(Connection connection) { this.connection = connection; }

    @FunctionalInterface public interface Bind { void apply(Bindings bindings) throws SQLException; }
    @FunctionalInterface public interface Row<R> { R read(ResultSet result) throws SQLException; }
    @FunctionalInterface public interface Work<R> { R run(Session session) throws SQLException; }

    /** Participate in an existing transaction without committing, rolling back, or closing its connection. */
    public static <R> R borrow(Connection connection, Work<R> work) throws SQLException {
        if (connection.getAutoCommit()) throw new IllegalStateException("Vev requires an explicit transaction");
        requirePostgres(connection);
        Session session = new Session(connection);
        try {
            R result = work.run(session);
            session.check();
            return result;
        } finally { session.active = false; }
    }

    static void requirePostgres(Connection connection) throws SQLException {
        var metadata = connection.getMetaData();
        if (!metadata.getDatabaseProductName().equals("PostgreSQL") || metadata.getDatabaseMajorVersion() != 18) {
            throw new IllegalArgumentException("Vev requires PostgreSQL 18");
        }
        if (Runtime.version().feature() != 27) throw new IllegalStateException("Vev requires JDK 27");
    }

    void check() {
        if (!active || Thread.currentThread() != owner) throw new IllegalStateException("Vev session escaped its thread or scope");
        if (failed) throw new IllegalStateException("Vev transaction failed; roll it back");
    }

    /** Materialize at most maxRows rows; exceeding the bound fails the transaction. */
    public <R> List<R> list(String sql, int maxRows, Bind bind, Row<R> row) throws SQLException {
        check();
        if (maxRows < 1) throw new IllegalArgumentException("Row bound must be positive");
        try (PreparedStatement statement = connection.prepareStatement(sql);
             Bindings bindings = new Bindings(connection, statement)) {
            statement.setFetchSize(Math.min(maxRows, 256));
            bind.apply(bindings);
            try (ResultSet result = statement.executeQuery()) {
                List<R> rows = new ArrayList<>();
                while (result.next()) {
                    if (rows.size() == maxRows) throw new IllegalStateException("Vev query exceeded its row bound: " + maxRows);
                    rows.add(row.read(result));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    /** Exactly one row. Zero or multiple rows fail the transaction. */
    public <R> R one(String sql, Bind bind, Row<R> row) throws SQLException {
        List<R> rows = list(sql, 1, bind, row);
        if (rows.isEmpty()) {
            failed = true;
            throw new IllegalStateException("Vev query expected one row, received none");
        }
        return rows.getFirst();
    }

    /** Zero or one row. Multiple rows fail the transaction. */
    public <R> R optional(String sql, Bind bind, Row<R> row) throws SQLException {
        List<R> rows = list(sql, 1, bind, row);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Execute an explicit SQL mutation and return its affected-row count. */
    public long execute(String sql, Bind bind) throws SQLException {
        check();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             Bindings bindings = new Bindings(connection, statement)) {
            bind.apply(bindings);
            return statement.executeLargeUpdate();
        } catch (SQLException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }
}
