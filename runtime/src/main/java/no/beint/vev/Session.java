package no.beint.vev;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

/** A thread-confined, lexical execution scope. Generated queries own their JDBC resources. */
public final class Session {
    public static final int DEFAULT_STREAM_FETCH_SIZE = 256;
    private static final ScopedValue<Session> CURRENT = ScopedValue.newInstance();

    static {
        if (Runtime.version().feature() < 27) throw new IllegalStateException("Vev requires JDK 27 or newer");
    }

    private final Connection connection;
    private final int tenantId;
    private final int queryTimeoutSeconds;
    private final Thread owner = Thread.currentThread();
    private boolean active = true;
    private boolean failed;

    Session(Connection connection, int tenantId, int queryTimeoutSeconds) {
        this.connection = connection;
        this.tenantId = tenantId;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /** Resolve only inside the owning transaction and thread; never propagate JDBC sessions to coroutines. */
    public static Session current() {
        if (!CURRENT.isBound()) throw new IllegalStateException("No active Vev transaction");
        Session session = CURRENT.get();
        session.check();
        return session;
    }

    public static Session tenant() {
        Session session = current();
        if (session.tenantId <= 0) {
            session.failed = true;
            throw new IllegalStateException("A tenant query requires a tenant transaction");
        }
        return session;
    }

    public int tenantId() {
        check();
        if (tenantId <= 0) throw new IllegalStateException("A global transaction has no tenant");
        return tenantId;
    }

    static boolean bound() { return CURRENT.isBound(); }

    @FunctionalInterface public interface Work<R> { R run(Session session) throws SQLException; }

    /**
     * Participate in a caller-owned transaction as a tenant. The caller commits, rolls back and closes the connection,
     * and must propagate failures to its transaction manager.
     */
    public static <R> R borrowTenant(Connection connection, int tenantId, Work<R> work) throws SQLException {
        return borrowTenant(connection, tenantId, null, work);
    }

    /** Like {@link #borrowTenant(Connection, int, Work)}, applying a per-statement query timeout. */
    public static <R> R borrowTenant(Connection connection, int tenantId, Duration queryTimeout, Work<R> work) throws SQLException {
        if (tenantId <= 0) throw new IllegalArgumentException("Tenant identifier must be positive");
        return run(connection, tenantId, timeoutSeconds(queryTimeout), work);
    }

    /** Participate in a caller-owned transaction without a tenant; only global queries may run. */
    public static <R> R borrowGlobal(Connection connection, Work<R> work) throws SQLException {
        return borrowGlobal(connection, null, work);
    }

    /** Like {@link #borrowGlobal(Connection, Work)}, applying a per-statement query timeout. */
    public static <R> R borrowGlobal(Connection connection, Duration queryTimeout, Work<R> work) throws SQLException {
        return run(connection, 0, timeoutSeconds(queryTimeout), work);
    }

    /** JDBC timeouts have whole-second precision, so a partial second rounds up rather than disabling the timeout. */
    static int timeoutSeconds(Duration timeout) {
        if (timeout == null) return 0;
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Query timeout must be positive");
        long seconds = timeout.getSeconds() + (timeout.getNano() > 0 ? 1 : 0);
        return (int) Math.min(seconds, Integer.MAX_VALUE);
    }

    static <R> R run(Connection connection, int tenantId, int queryTimeoutSeconds, Work<R> work) throws SQLException {
        if (bound()) throw new IllegalStateException("Nested Vev transactions are not supported");
        if (connection.getAutoCommit()) throw new IllegalStateException("Vev requires an explicit transaction");
        Session session = new Session(connection, tenantId, queryTimeoutSeconds);
        try {
            R result = ScopedValue.where(CURRENT, session).call(() -> work.run(session));
            session.check();
            return result;
        } finally { session.active = false; }
    }

    static void requirePostgres(Connection connection) throws SQLException {
        var metadata = connection.getMetaData();
        if (!metadata.getDatabaseProductName().equals("PostgreSQL") || metadata.getDatabaseMajorVersion() != 19) {
            throw new IllegalArgumentException("Vev requires PostgreSQL 19");
        }
    }

    void check() {
        if (!active || Thread.currentThread() != owner) throw new IllegalStateException("Vev session escaped its thread or scope");
        if (failed) throw new IllegalStateException("Vev transaction failed; roll it back");
    }

    /** Prepare a statement in this scope, applying the configured query timeout if any. */
    public PreparedStatement prepare(String sql) throws SQLException {
        check();
        PreparedStatement statement = connection.prepareStatement(sql);
        if (queryTimeoutSeconds > 0) statement.setQueryTimeout(queryTimeoutSeconds);
        return statement;
    }

    /**
     * Prepare a statement PostgreSQL plans with each execution's parameter values. pgJDBC otherwise switches to a
     * server-side prepared statement after a few executions, whose cached generic plan cannot drop optional filters.
     */
    public PreparedStatement prepareCustomPlan(String sql) throws SQLException {
        PreparedStatement statement = prepare(sql);
        statement.unwrap(org.postgresql.PGStatement.class).setPrepareThreshold(0);
        return statement;
    }

    /** Return the active transaction connection for statement-local JDBC helpers such as array creation. */
    public Connection connection() {
        check();
        return connection;
    }

    /** Poison the scope after a SQL, decoding, or cardinality failure so the caller rolls back. */
    public void markFailed() {
        failed = true;
    }
}
