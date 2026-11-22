package no.beint.vev;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

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

    Session(Connection connection, int tenantId) { this(connection, tenantId, 0); }

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

    @FunctionalInterface public interface Bind { void apply(Bindings bindings) throws SQLException; }
    @FunctionalInterface public interface Row<R> { R read(ResultSet result) throws SQLException; }
    @FunctionalInterface public interface Work<R> { R run(Session session) throws SQLException; }

    /** Participate in an existing transaction without committing, rolling back, or closing its connection. */
    public static <R> R borrow(Connection connection, Work<R> work) throws SQLException {
        return borrow(connection, 0, 0, work);
    }

    /** Participate in a caller-owned transaction that must have a positive tenant identifier. */
    public static <R> R borrowTenant(Connection connection, int tenantId, Work<R> work) throws SQLException {
        return borrowTenant(connection, tenantId, 0, work);
    }

    /** Participate in a caller-owned tenant transaction with a per-statement JDBC query timeout in seconds. */
    public static <R> R borrowTenant(Connection connection, int tenantId, int queryTimeoutSeconds, Work<R> work) throws SQLException {
        if (tenantId <= 0) throw new IllegalArgumentException("Tenant identifier must be positive");
        return borrow(connection, tenantId, queryTimeoutSeconds, work);
    }

    public static <R> R borrow(Connection connection, int tenantId, Work<R> work) throws SQLException {
        return borrow(connection, tenantId, 0, work);
    }

    public static <R> R borrow(Connection connection, int tenantId, int queryTimeoutSeconds, Work<R> work) throws SQLException {
        if (tenantId < 0) throw new IllegalArgumentException("Tenant identifier cannot be negative");
        if (queryTimeoutSeconds < 0) throw new IllegalArgumentException("Query timeout must not be negative");
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

    /** Materialize at most maxRows rows; exceeding the bound fails the transaction. */
    public <R> List<R> list(String sql, int maxRows, Bind bind, Row<R> row) throws SQLException {
        check();
        if (maxRows < 1) throw new IllegalArgumentException("Row bound must be positive");
        try (PreparedStatement statement = prepare(sql);
             Bindings bindings = new Bindings(connection, statement)) {
            bind.apply(bindings);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return List.of();
                R first = Objects.requireNonNull(row.read(result), "Vev row mapper returned null");
                if (!result.next()) return List.of(first);
                if (maxRows == 1) throw new IllegalStateException("Vev query exceeded its row bound: 1");
                List<R> rows = new ArrayList<>(Math.min(maxRows, 16));
                rows.add(first);
                do {
                    if (rows.size() == maxRows) throw new IllegalStateException("Vev query exceeded its row bound: " + maxRows);
                    rows.add(Objects.requireNonNull(row.read(result), "Vev row mapper returned null"));
                } while (result.next());
                return Collections.unmodifiableList(rows);
            }
        } catch (SQLException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    /** Stream rows through a server-side cursor without materializing the full result set in memory. */
    public <R> void stream(String sql, int fetchSize, Bind bind, Row<R> row, Consumer<R> consumer) throws SQLException {
        check();
        if (fetchSize < 1) throw new IllegalArgumentException("Fetch size must be positive");
        Objects.requireNonNull(consumer, "Row consumer must not be null");
        try (PreparedStatement statement = prepare(sql);
             Bindings bindings = new Bindings(connection, statement)) {
            statement.setFetchSize(fetchSize);
            bind.apply(bindings);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    consumer.accept(Objects.requireNonNull(row.read(result), "Vev row mapper returned null"));
                }
            }
        } catch (SQLException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    /** Exactly one row. Zero or multiple rows fail the transaction. */
    public <R> R one(String sql, Bind bind, Row<R> row) throws SQLException {
        return single(sql, bind, row, true);
    }

    /** Zero or one row. Multiple rows fail the transaction. */
    public <R> R optional(String sql, Bind bind, Row<R> row) throws SQLException {
        return single(sql, bind, row, false);
    }

    private <R> R single(String sql, Bind bind, Row<R> row, boolean required) throws SQLException {
        check();
        try (PreparedStatement statement = prepare(sql);
             Bindings bindings = new Bindings(connection, statement)) {
            bind.apply(bindings);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    if (required) throw new IllegalStateException("Vev query expected one row, received none");
                    return null;
                }
                R value = Objects.requireNonNull(row.read(result), "Vev row mapper returned null");
                if (result.next()) throw new IllegalStateException("Vev query expected at most one row, received multiple");
                return value;
            }
        } catch (SQLException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    /**
     * Call a void-returning function for its effect, such as an advisory lock, discarding any
     * result set. Nothing is read, so there is no value to map.
     */
    public void call(String sql, Bind bind) throws SQLException {
        check();
        try (PreparedStatement statement = prepare(sql);
             Bindings bindings = new Bindings(connection, statement)) {
            bind.apply(bindings);
            statement.execute();
        } catch (SQLException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    /** Execute an explicit SQL mutation and return its affected-row count. */
    public long execute(String sql, Bind bind) throws SQLException {
        check();
        try (PreparedStatement statement = prepare(sql);
             Bindings bindings = new Bindings(connection, statement)) {
            bind.apply(bindings);
            return statement.executeLargeUpdate();
        } catch (SQLException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }
}
