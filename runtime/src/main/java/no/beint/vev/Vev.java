package no.beint.vev;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Explicit standalone transactions. Use Session.borrow for framework-owned transactions. */
public final class Vev {
    private final DataSource source;
    private final int isolation;

    public Vev(DataSource source) { this(source, Connection.TRANSACTION_READ_COMMITTED); }

    public Vev(DataSource source, int isolation) {
        this.source = java.util.Objects.requireNonNull(source);
        if (isolation != Connection.TRANSACTION_READ_COMMITTED
                && isolation != Connection.TRANSACTION_REPEATABLE_READ
                && isolation != Connection.TRANSACTION_SERIALIZABLE) {
            throw new IllegalArgumentException("Unsupported PostgreSQL transaction isolation");
        }
        this.isolation = isolation;
    }

    public <R> R read(Session.Work<R> work) throws SQLException { return transaction(true, work); }
    public <R> R write(Session.Work<R> work) throws SQLException { return transaction(false, work); }
    public <R> R read(int tenantId, TenantSession.Work<R> work) throws SQLException { return read(session -> TenantSession.within(session, tenantId, work)); }
    public <R> R write(int tenantId, TenantSession.Work<R> work) throws SQLException { return write(session -> TenantSession.within(session, tenantId, work)); }

    private <R> R transaction(boolean readOnly, Session.Work<R> work) throws SQLException {
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
            R result = Session.borrow(connection, work);
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
