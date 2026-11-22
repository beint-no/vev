package no.beint.vev;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;

/** Explicit JDBC transactions; the connection pool owns the default isolation configuration. */
public final class Vev {
    public static final int DEFAULT_ISOLATION = -1;
    private final DataSource source;
    private final int isolation;
    private final int queryTimeoutSeconds;
    private volatile boolean verified;

    public Vev(DataSource source) { this(source, DEFAULT_ISOLATION, null); }

    public Vev(DataSource source, int isolation) { this(source, isolation, null); }

    /** A null queryTimeout leaves statements without a timeout. */
    public Vev(DataSource source, int isolation, Duration queryTimeout) {
        this.source = java.util.Objects.requireNonNull(source);
        if (isolation != DEFAULT_ISOLATION
                && isolation != Connection.TRANSACTION_READ_COMMITTED
                && isolation != Connection.TRANSACTION_REPEATABLE_READ
                && isolation != Connection.TRANSACTION_SERIALIZABLE) {
            throw new IllegalArgumentException("Unsupported PostgreSQL transaction isolation");
        }
        this.isolation = isolation;
        this.queryTimeoutSeconds = Session.timeoutSeconds(queryTimeout);
    }

    public <R> R read(int tenantId, Session.Work<R> work) throws SQLException { return tenant(true, tenantId, work); }
    public <R> R write(int tenantId, Session.Work<R> work) throws SQLException { return tenant(false, tenantId, work); }
    public <R> R readGlobal(Session.Work<R> work) throws SQLException { return transaction(true, 0, work); }
    public <R> R writeGlobal(Session.Work<R> work) throws SQLException { return transaction(false, 0, work); }

    private <R> R tenant(boolean readOnly, int tenantId, Session.Work<R> work) throws SQLException {
        if (tenantId <= 0) throw new IllegalArgumentException("Tenant identifier must be positive");
        return transaction(readOnly, tenantId, work);
    }

    private <R> R transaction(boolean readOnly, int tenantId, Session.Work<R> work) throws SQLException {
        if (Session.bound()) throw new IllegalStateException("Nested Vev transactions are not supported");
        Connection connection = source.getConnection();
        Throwable failure = null;
        boolean committed = false;
        boolean started = false;
        try {
            if (!verified) {
                Session.requirePostgres(connection);
                verified = true;
            }
            if (isolation != DEFAULT_ISOLATION) connection.setTransactionIsolation(isolation);
            if (connection.getAutoCommit()) connection.setAutoCommit(false);
            started = true;
            if (readOnly && !connection.isReadOnly()) {
                // Transaction-scoped read-only avoids mutating pooled session state and extra reset round-trips.
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET TRANSACTION READ ONLY");
                }
            }
            R result = Session.run(connection, tenantId, queryTimeoutSeconds, work);
            connection.commit();
            committed = true;
            return result;
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            if (started) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
            }
            throw exception;
        } finally {
            try { connection.close(); }
            catch (SQLException close) {
                if (failure != null) failure.addSuppressed(close);
                else if (!committed) throw close;
                else System.getLogger(Vev.class.getName()).log(System.Logger.Level.WARNING,
                        "Vev committed, but connection cleanup failed", close);
            }
        }
    }
}
