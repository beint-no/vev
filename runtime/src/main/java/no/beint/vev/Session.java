package no.beint.vev;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Thread-confined, lexical transaction scopes and single-connection borrow helpers.
 * Generated queries receive one of the four sealed capability scopes directly.
 */
public final class Session {
    public static final int DEFAULT_STREAM_FETCH_SIZE = 256;

    static {
        if (Runtime.version().feature() < 27) throw new IllegalStateException("Vev requires JDK 27 or newer");
    }

    private Session() {}

    static final class ActiveTx {
        final Connection connection;
        final boolean readOnly;
        final int isolation;
        int queryTimeoutSeconds;
        long queryTimeoutMillis;
        long lockTimeoutMillis;
        final Thread owner = Thread.currentThread();
        final Db.TransactionBackend backend;
        final UnaryOperator<Runnable> callbackDecorator;
        boolean active = true;
        boolean failed;
        List<Runnable> beforeCommit;
        List<Runnable> afterCommit;
        List<Runnable> afterCompletion;

        ActiveTx(Connection connection, boolean readOnly, int isolation, long queryTimeoutMillis, long lockTimeoutMillis,
                 Db.TransactionBackend backend, UnaryOperator<Runnable> callbackDecorator) {
            this.connection = connection;
            this.readOnly = readOnly;
            this.isolation = isolation;
            this.queryTimeoutMillis = queryTimeoutMillis;
            this.queryTimeoutSeconds = millisToSeconds(queryTimeoutMillis);
            this.lockTimeoutMillis = lockTimeoutMillis;
            this.backend = backend;
            this.callbackDecorator = callbackDecorator;
        }

        void check() {
            if (!active || Thread.currentThread() != owner) {
                throw new VevSessionException("Vev session escaped its thread or scope");
            }
            if (failed) {
                throw new VevSessionException("Vev transaction failed; roll it back");
            }
        }

        void beforeCommit(Runnable action) {
            Objects.requireNonNull(action, "action");
            check();
            if (readOnly) throw new VevSessionException("beforeCommit requires a write transaction");
            if (backend != null) {
                backend.beforeCommit(action);
                return;
            }
            if (beforeCommit == null) beforeCommit = new ArrayList<>(2);
            beforeCommit.add(action);
        }

        void afterCommit(Runnable action) {
            Objects.requireNonNull(action, "action");
            check();
            Runnable decorated = decorate(action);
            if (backend != null) {
                backend.afterCommit(decorated);
                return;
            }
            if (afterCommit == null) afterCommit = new ArrayList<>(2);
            afterCommit.add(decorated);
        }

        void afterCompletion(Runnable action) {
            Objects.requireNonNull(action, "action");
            check();
            Runnable decorated = decorate(action);
            if (backend != null) {
                backend.afterCompletion(decorated);
                return;
            }
            if (afterCompletion == null) afterCompletion = new ArrayList<>(2);
            afterCompletion.add(decorated);
        }

        void truncateCallbacks(int beforeCommitSize, int afterCommitSize, int afterCompletionSize) {
            truncate(beforeCommit, beforeCommitSize);
            truncate(afterCommit, afterCommitSize);
            truncate(afterCompletion, afterCompletionSize);
        }

        private static void truncate(List<Runnable> list, int size) {
            if (list == null) return;
            while (list.size() > size) list.removeLast();
        }

        Runnable decorate(Runnable action) {
            return callbackDecorator != null ? Objects.requireNonNull(callbackDecorator.apply(action)) : action;
        }

        void runBeforeCommit() {
            if (beforeCommit == null) return;
            int index = 0;
            while (index < beforeCommit.size()) {
                check();
                beforeCommit.get(index++).run();
            }
        }

        void runAfterCommit() {
            runAll(afterCommit);
        }

        void runAfterCompletion() {
            runAll(afterCompletion);
        }

        private static void runAll(List<Runnable> actions) {
            if (actions == null) return;
            Throwable failure = null;
            for (Runnable action : actions) {
                try { action.run(); }
                catch (RuntimeException | Error exception) {
                    if (failure == null) failure = exception; else failure.addSuppressed(exception);
                }
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }

        PreparedStatement prepare(String sql) throws SQLException {
            check();
            PreparedStatement statement = connection.prepareStatement(sql);
            if (queryTimeoutSeconds > 0) statement.setQueryTimeout(queryTimeoutSeconds);
            return statement;
        }

        PreparedStatement prepareCustomPlan(String sql) throws SQLException {
            PreparedStatement statement = prepare(sql);
            statement.unwrap(org.postgresql.PGStatement.class).setPrepareThreshold(0);
            return statement;
        }

        Connection connection() {
            check();
            return connection;
        }

        void markFailed() {
            failed = true;
        }
    }

    abstract static class ScopeBase {
        final ActiveTx tx;
        final Thread owner = Thread.currentThread();
        boolean active = true;

        ScopeBase(ActiveTx tx) { this.tx = tx; }

        void checkScope() {
            if (!active || Thread.currentThread() != owner) {
                throw new VevSessionException("Vev session escaped its thread or scope");
            }
            tx.check();
        }

        void deactivate() { active = false; }

        public boolean isReadOnly() { checkScope(); return tx.readOnly; }
        public void afterCommit(Runnable action) { checkScope(); tx.afterCommit(action); }
        public void afterCompletion(Runnable action) { checkScope(); tx.afterCompletion(action); }
        public Connection connection() { checkScope(); return tx.connection(); }
        public PreparedStatement prepare(String sql) throws SQLException { checkScope(); return tx.prepare(sql); }
        public PreparedStatement prepareCustomPlan(String sql) throws SQLException { checkScope(); return tx.prepareCustomPlan(sql); }
        public void markFailed() { tx.markFailed(); }
    }

    /** Global read capability handle. */
    public static final class GlobalRead extends ScopeBase implements ReadScope {
        GlobalRead(ActiveTx tx) { super(tx); }
    }

    /** Global write capability handle. */
    public static final class GlobalWrite extends ScopeBase implements WriteScope {
        GlobalWrite(ActiveTx tx) { super(tx); }
        @Override public void beforeCommit(Runnable action) { checkScope(); tx.beforeCommit(action); }
    }

    /** Tenant read capability handle. */
    public static final class TenantRead extends ScopeBase implements TenantReadScope {
        private final int tenantId;
        TenantRead(ActiveTx tx, int tenantId) { super(tx); this.tenantId = tenantId; }
        @Override public int tenantId() { checkScope(); return tenantId; }
    }

    /** Tenant write capability handle. */
    public static final class TenantWrite extends ScopeBase implements TenantWriteScope {
        private final int tenantId;
        TenantWrite(ActiveTx tx, int tenantId) { super(tx); this.tenantId = tenantId; }
        @Override public int tenantId() { checkScope(); return tenantId; }
        @Override public void beforeCommit(Runnable action) {
            Objects.requireNonNull(action, "action");
            checkScope();
            tx.beforeCommit(Db.bindTenant(tenantId, action));
        }
    }

    /** Borrow a caller-owned transaction connection for global read queries. */
    public static <R> R borrowRead(Connection connection, Db.ReadWork<R> work) throws SQLException {
        return borrowRead(connection, null, work);
    }

    public static <R> R borrowRead(Connection connection, Duration queryTimeout, Db.ReadWork<R> work) throws SQLException {
        ActiveTx tx = borrowedTx(connection, true, queryTimeout);
        GlobalRead scope = new GlobalRead(tx);
        try {
            R result = work.run(scope);
            tx.check();
            return result;
        } finally {
            scope.deactivate();
            tx.active = false;
        }
    }

    /** Borrow a caller-owned transaction connection for global read or write queries. */
    public static <R> R borrowWrite(Connection connection, Db.WriteWork<R> work) throws SQLException {
        return borrowWrite(connection, null, work);
    }

    public static <R> R borrowWrite(Connection connection, Duration queryTimeout, Db.WriteWork<R> work) throws SQLException {
        ActiveTx tx = borrowedTx(connection, false, queryTimeout);
        GlobalWrite scope = new GlobalWrite(tx);
        try {
            R result = work.run(scope);
            tx.check();
            return result;
        } finally {
            scope.deactivate();
            tx.active = false;
        }
    }

    /** Borrow a caller-owned transaction connection for tenant read queries. */
    public static <R> R borrowTenantRead(Connection connection, int tenantId, Db.TenantReadWork<R> work) throws SQLException {
        return borrowTenantRead(connection, tenantId, null, work);
    }

    public static <R> R borrowTenantRead(Connection connection, int tenantId, Duration queryTimeout, Db.TenantReadWork<R> work) throws SQLException {
        requireTenant(tenantId);
        ActiveTx tx = borrowedTx(connection, true, queryTimeout);
        TenantRead scope = new TenantRead(tx, tenantId);
        try {
            R result = work.run(scope);
            tx.check();
            return result;
        } finally {
            scope.deactivate();
            tx.active = false;
        }
    }

    /** Borrow a caller-owned transaction connection for tenant read or write queries. */
    public static <R> R borrowTenantWrite(Connection connection, int tenantId, Db.TenantWriteWork<R> work) throws SQLException {
        return borrowTenantWrite(connection, tenantId, null, work);
    }

    public static <R> R borrowTenantWrite(Connection connection, int tenantId, Duration queryTimeout, Db.TenantWriteWork<R> work) throws SQLException {
        requireTenant(tenantId);
        ActiveTx tx = borrowedTx(connection, false, queryTimeout);
        TenantWrite scope = new TenantWrite(tx, tenantId);
        try {
            R result = work.run(scope);
            tx.check();
            return result;
        } finally {
            scope.deactivate();
            tx.active = false;
        }
    }

    private static ActiveTx borrowedTx(Connection connection, boolean readOnly, Duration queryTimeout) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (connection.getAutoCommit()) throw new VevSessionException("Vev requires an explicit transaction");
        return new ActiveTx(connection, readOnly, Db.DEFAULT_ISOLATION, timeoutMillis(queryTimeout), 0L, null, null);
    }

    static int requireTenant(int tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("Tenant identifier must be positive");
        return tenantId;
    }

    static long timeoutMillis(Duration timeout) {
        return timeoutMillis(timeout, "Query timeout");
    }

    static long timeoutMillis(Duration timeout, String name) {
        if (timeout == null) return 0;
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException(name + " must be positive");
        long millis = timeout.toMillis();
        return millis == 0 ? 1 : millis;
    }

    /** JDBC timeouts have whole-second precision, so a partial second rounds up rather than disabling the timeout. */
    static int millisToSeconds(long millis) {
        if (millis <= 0) return 0;
        long seconds = (millis + 999) / 1000;
        return (int) Math.min(seconds, Integer.MAX_VALUE);
    }

    static int timeoutSeconds(Duration timeout) {
        return millisToSeconds(timeoutMillis(timeout));
    }

    static void requirePostgres(Connection connection) throws SQLException {
        var metadata = connection.getMetaData();
        if (!metadata.getDatabaseProductName().equals("PostgreSQL") || metadata.getDatabaseMajorVersion() != 19) {
            throw new IllegalArgumentException("Vev requires PostgreSQL 19");
        }
    }
}
