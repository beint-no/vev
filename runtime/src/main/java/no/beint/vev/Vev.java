package no.beint.vev;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Explicit standalone transactions. Use Session.borrow for framework-owned transactions. */
public final class Vev {
    private final DataSource source;
    private final int isolation;
    private final boolean rowLevelSecurity;

    public Vev(DataSource source) { this(source, Connection.TRANSACTION_READ_COMMITTED); }

    public Vev(DataSource source, int isolation) {
        this(source, isolation, false);
    }

    public Vev(DataSource source, int isolation, boolean rowLevelSecurity) {
        this.rowLevelSecurity = rowLevelSecurity;
        this.source = java.util.Objects.requireNonNull(source);
        if (isolation != Connection.TRANSACTION_READ_COMMITTED
                && isolation != Connection.TRANSACTION_REPEATABLE_READ
                && isolation != Connection.TRANSACTION_SERIALIZABLE) {
            throw new IllegalArgumentException("Unsupported PostgreSQL transaction isolation");
        }
        this.isolation = isolation;
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
        boolean owned = false;
        try {
            if (!connection.getAutoCommit()) throw new IllegalStateException("Vev requires a fresh connection");
            owned = true;
            connection.setTransactionIsolation(isolation);
            connection.setReadOnly(readOnly);
            connection.setAutoCommit(false);
            if (rowLevelSecurity) {
                try (var statement = connection.prepareStatement("SELECT set_config('vev.tenant_id', ?, true)")) {
                    statement.setString(1, tenantId > 0 ? Integer.toString(tenantId) : "");
                    statement.execute();
                }
            }
            R result = Session.borrow(connection, tenantId, work);
            connection.commit();
            committed = true;
            return result;
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            if (owned) {
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
