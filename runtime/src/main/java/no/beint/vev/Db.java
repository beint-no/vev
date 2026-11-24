package no.beint.vev;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;

/**
 * Re-entrant, capability-typed PostgreSQL transactions with before/after-commit lifecycle hooks.
 *
 * <p>Outermost calls own the JDBC connection, commit, rollback, and close. Nested calls join the
 * enclosing transaction on the same connection while rejecting read-to-write escalation and
 * cross-tenant switching. {@code writeNew} opens an independent transaction; {@code writeIsolated}
 * rejects any enclosing transaction.
 */
public final class Db {
    public static final int DEFAULT_ISOLATION = -1;

    private static final ScopedValue<Session.ActiveTx> CURRENT_TX = ScopedValue.newInstance();
    private static final ScopedValue<Integer> CURRENT_TENANT = ScopedValue.newInstance();

    @FunctionalInterface public interface ReadWork<R> { R run(ReadScope tx) throws SQLException; }
    @FunctionalInterface public interface WriteWork<R> { R run(WriteScope tx) throws SQLException; }
    @FunctionalInterface public interface TenantReadWork<R> { R run(TenantReadScope tx) throws SQLException; }
    @FunctionalInterface public interface TenantWriteWork<R> { R run(TenantWriteScope tx) throws SQLException; }

    /** Observes outermost database transactions for slow/stuck transaction monitoring. */
    public interface TransactionListener {
        Object onBegin(boolean readOnly, int tenantId);
        void onEnd(Object token, boolean committed, Throwable failure);
    }

    /** Optional SPI for bridging an external transaction manager during incremental migrations. */
    public interface TransactionBackend {
        @FunctionalInterface interface BackendWork<R> { R run(Connection connection, Duration remainingTimeout) throws SQLException; }

        <R> R execute(boolean readOnly, boolean requiresNew, BackendWork<R> work) throws SQLException;
        boolean isTransactionActive();
        boolean isCurrentTransactionReadOnly();
        void beforeCommit(Runnable action);
        void afterCommit(Runnable action);
        void afterCompletion(Runnable action);
    }

    private final DataSource source;
    private final TransactionBackend backend;
    private final int isolation;
    private final int queryTimeoutSeconds;
    private final IntSupplier tenantSupplier;
    private final TransactionListener listener;
    private final UnaryOperator<Runnable> callbackDecorator;
    private final Function<SQLException, RuntimeException> exceptionTranslator;
    private volatile boolean verified;

    public Db(DataSource source) {
        this(builder(source));
    }

    public Db(DataSource source, int isolation) {
        this(builder(source).isolation(isolation));
    }

    /** A null queryTimeout leaves statements without a timeout. */
    public Db(DataSource source, int isolation, Duration queryTimeout) {
        this(builder(source).isolation(isolation).queryTimeout(queryTimeout));
    }

    private Db(Builder builder) {
        this.source = builder.source;
        this.backend = builder.backend;
        this.isolation = builder.isolation;
        this.queryTimeoutSeconds = Session.timeoutSeconds(builder.queryTimeout);
        this.tenantSupplier = builder.tenantSupplier;
        this.listener = builder.listener;
        this.callbackDecorator = builder.callbackDecorator;
        this.exceptionTranslator = builder.exceptionTranslator;
    }

    public static Builder builder(DataSource source) {
        return new Builder(Objects.requireNonNull(source, "source"), null);
    }

    public static Builder builder(TransactionBackend backend) {
        return new Builder(null, Objects.requireNonNull(backend, "backend"));
    }

    public static final class Builder {
        private final DataSource source;
        private final TransactionBackend backend;
        private int isolation = DEFAULT_ISOLATION;
        private Duration queryTimeout;
        private IntSupplier tenantSupplier;
        private TransactionListener listener;
        private UnaryOperator<Runnable> callbackDecorator;
        private Function<SQLException, RuntimeException> exceptionTranslator = VevSqlException::new;

        private Builder(DataSource source, TransactionBackend backend) {
            this.source = source;
            this.backend = backend;
        }

        public Builder isolation(int isolation) {
            if (isolation != DEFAULT_ISOLATION
                    && isolation != Connection.TRANSACTION_READ_COMMITTED
                    && isolation != Connection.TRANSACTION_REPEATABLE_READ
                    && isolation != Connection.TRANSACTION_SERIALIZABLE) {
                throw new IllegalArgumentException("Unsupported PostgreSQL transaction isolation");
            }
            this.isolation = isolation;
            return this;
        }

        public Builder queryTimeout(Duration queryTimeout) {
            this.queryTimeout = queryTimeout;
            return this;
        }

        public Builder tenantSupplier(IntSupplier tenantSupplier) {
            this.tenantSupplier = tenantSupplier;
            return this;
        }

        public Builder transactionListener(TransactionListener listener) {
            this.listener = listener;
            return this;
        }

        public Builder callbackDecorator(UnaryOperator<Runnable> callbackDecorator) {
            this.callbackDecorator = callbackDecorator;
            return this;
        }

        public Builder exceptionTranslator(Function<SQLException, RuntimeException> exceptionTranslator) {
            this.exceptionTranslator = Objects.requireNonNull(exceptionTranslator, "exceptionTranslator");
            return this;
        }

        public Db build() { return new Db(this); }
    }

    // ---- Global transactions ----

    public <R> R read(ReadWork<R> work) {
        Objects.requireNonNull(work, "work");
        return execute(true, false, false, 0, tx -> {
            var scope = new Session.GlobalRead(tx);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R write(WriteWork<R> work) {
        Objects.requireNonNull(work, "work");
        return execute(false, false, false, 0, tx -> {
            var scope = new Session.GlobalWrite(tx);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R writeNew(WriteWork<R> work) {
        Objects.requireNonNull(work, "work");
        return execute(false, true, false, 0, tx -> {
            var scope = new Session.GlobalWrite(tx);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R writeIsolated(WriteWork<R> work) {
        Objects.requireNonNull(work, "work");
        return execute(false, false, true, 0, tx -> {
            var scope = new Session.GlobalWrite(tx);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    // ---- Explicit-tenant transactions ----

    public <R> R read(int tenantId, TenantReadWork<R> work) {
        Session.requireTenant(tenantId);
        Objects.requireNonNull(work, "work");
        return execute(true, false, false, tenantId, tx -> {
            var scope = new Session.TenantRead(tx, tenantId);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R write(int tenantId, TenantWriteWork<R> work) {
        Session.requireTenant(tenantId);
        Objects.requireNonNull(work, "work");
        return execute(false, false, false, tenantId, tx -> {
            var scope = new Session.TenantWrite(tx, tenantId);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R writeNew(int tenantId, TenantWriteWork<R> work) {
        Session.requireTenant(tenantId);
        Objects.requireNonNull(work, "work");
        return execute(false, true, false, tenantId, tx -> {
            var scope = new Session.TenantWrite(tx, tenantId);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R writeIsolated(int tenantId, TenantWriteWork<R> work) {
        Session.requireTenant(tenantId);
        Objects.requireNonNull(work, "work");
        return execute(false, false, true, tenantId, tx -> {
            var scope = new Session.TenantWrite(tx, tenantId);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    // ---- Current-tenant transactions (uses enclosing tenant scope if active, else tenantSupplier) ----

    public <R> R tenantRead(TenantReadWork<R> work) {
        return read(resolveTenantId(), work);
    }

    public <R> R tenantWrite(TenantWriteWork<R> work) {
        return write(resolveTenantId(), work);
    }

    public <R> R tenantWriteNew(TenantWriteWork<R> work) {
        return writeNew(resolveTenantId(), work);
    }

    public <R> R tenantWriteIsolated(TenantWriteWork<R> work) {
        return writeIsolated(resolveTenantId(), work);
    }

    // ---- Introspection & lifecycle hooks ----

    public boolean inTransaction() {
        if (currentTxOrNull() != null) return true;
        return backend != null && backend.isTransactionActive();
    }

    public boolean inWriteTransaction() {
        Session.ActiveTx tx = currentTxOrNull();
        if (tx != null) return !tx.readOnly;
        return backend != null && backend.isTransactionActive() && !backend.isCurrentTransactionReadOnly();
    }

    public void beforeCommit(Runnable action) {
        Objects.requireNonNull(action, "action");
        Session.ActiveTx tx = currentTxOrNull();
        if (tx != null) {
            tx.beforeCommit(action);
            return;
        }
        if (backend != null && backend.isTransactionActive()) {
            if (backend.isCurrentTransactionReadOnly()) {
                throw new VevSessionException("beforeCommit requires a write transaction");
            }
            backend.beforeCommit(action);
            return;
        }
        throw new VevSessionException("beforeCommit requires an enclosing write transaction");
    }

    public void afterCommit(Runnable action) {
        Objects.requireNonNull(action, "action");
        Session.ActiveTx tx = currentTxOrNull();
        if (tx != null) {
            tx.afterCommit(action);
            return;
        }
        Runnable decorated = decorate(action);
        if (backend != null && backend.isTransactionActive()) {
            backend.afterCommit(decorated);
            return;
        }
        decorated.run();
    }

    public void afterCompletion(Runnable action) {
        Objects.requireNonNull(action, "action");
        Session.ActiveTx tx = currentTxOrNull();
        if (tx != null) {
            tx.afterCompletion(action);
            return;
        }
        Runnable decorated = decorate(action);
        if (backend != null && backend.isTransactionActive()) {
            backend.afterCompletion(decorated);
            return;
        }
        decorated.run();
    }

    // ---- Execution core ----

    @FunctionalInterface private interface InternalWork<R> { R run(Session.ActiveTx tx) throws SQLException; }

    private int resolveTenantId() {
        int enclosing = currentTenantOrZero();
        if (enclosing > 0) return enclosing;
        if (tenantSupplier == null) {
            throw new VevSessionException("No tenantSupplier configured on Db; pass an explicit tenantId or configure tenantSupplier");
        }
        return Session.requireTenant(tenantSupplier.getAsInt());
    }

    private Session.ActiveTx currentTxOrNull() {
        return CURRENT_TX.isBound() ? CURRENT_TX.get() : null;
    }

    private int currentTenantOrZero() {
        return CURRENT_TENANT.isBound() ? CURRENT_TENANT.get() : 0;
    }

    private Runnable decorate(Runnable action) {
        return callbackDecorator != null ? Objects.requireNonNull(callbackDecorator.apply(action)) : action;
    }

    private RuntimeException translate(SQLException failure) {
        return Objects.requireNonNull(exceptionTranslator.apply(failure));
    }

    private <R> R execute(boolean readOnly, boolean requiresNew, boolean isolated, int tenantId, InternalWork<R> work) {
        if (isolated && inTransaction()) {
            throw new VevSessionException("db.writeIsolated must not run inside another transaction");
        }
        Session.ActiveTx enclosing = currentTxOrNull();
        if (enclosing != null && !requiresNew) {
            return joinEnclosing(enclosing, readOnly, tenantId, work);
        }
        if (backend != null) {
            return executeWithBackend(readOnly, requiresNew, tenantId, work);
        }
        return executeWithDataSource(readOnly, tenantId, work);
    }

    private <R> R joinEnclosing(Session.ActiveTx enclosing, boolean readOnly, int tenantId, InternalWork<R> work) {
        enclosing.check();
        if (!readOnly && enclosing.readOnly) {
            throw new VevSessionException("Cannot start a write transaction inside a read-only transaction");
        }
        int enclosingTenant = currentTenantOrZero();
        if (tenantId > 0 && enclosingTenant > 0 && tenantId != enclosingTenant) {
            throw new VevSessionException("Cannot switch tenant from " + enclosingTenant + " to " + tenantId
                    + " inside an active tenant transaction; use writeNew for a separate tenant transaction");
        }
        try {
            if (tenantId > 0 && enclosingTenant == 0) {
                return ScopedValue.where(CURRENT_TENANT, tenantId).call(() -> work.run(enclosing));
            }
            return work.run(enclosing);
        } catch (SQLException failure) {
            enclosing.markFailed();
            throw translate(failure);
        }
    }

    private <R> R executeWithBackend(boolean readOnly, boolean requiresNew, int tenantId, InternalWork<R> work) {
        if (!requiresNew && !readOnly && backend.isTransactionActive() && backend.isCurrentTransactionReadOnly()) {
            throw new VevSessionException("Cannot start a write transaction inside a read-only transaction");
        }
        try {
            return backend.execute(readOnly, requiresNew, (connection, remainingTimeout) -> {
                if (connection.getAutoCommit()) {
                    throw new VevSessionException("Vev requires an explicit transaction");
                }
                int timeoutSec = remainingTimeout != null ? Session.timeoutSeconds(remainingTimeout) : queryTimeoutSeconds;
                Session.ActiveTx tx = new Session.ActiveTx(connection, readOnly, timeoutSec, backend, callbackDecorator);
                try {
                    R result = ScopedValue.where(CURRENT_TX, tx).where(CURRENT_TENANT, tenantId).call(() -> work.run(tx));
                    if (tx.failed) throw new VevSessionException("Vev transaction failed; roll it back");
                    return result;
                } catch (SQLException failure) {
                    tx.markFailed();
                    throw failure;
                } finally {
                    tx.active = false;
                }
            });
        } catch (SQLException failure) {
            throw translate(failure);
        }
    }

    private <R> R executeWithDataSource(boolean readOnly, int tenantId, InternalWork<R> work) {
        Object listenerToken = listener != null ? listener.onBegin(readOnly, tenantId) : null;
        Connection connection = null;
        Throwable failure = null;
        boolean committed = false;
        boolean started = false;
        Session.ActiveTx tx = null;
        R result;
        try {
            connection = source.getConnection();
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
            Session.ActiveTx activeTx = new Session.ActiveTx(connection, readOnly, queryTimeoutSeconds, null, callbackDecorator);
            tx = activeTx;
            result = ScopedValue.where(CURRENT_TX, activeTx).where(CURRENT_TENANT, tenantId).call(() -> {
                R value = work.run(activeTx);
                if (!readOnly) activeTx.runBeforeCommit();
                return value;
            });
            if (activeTx.failed) {
                throw new VevSessionException("Vev transaction failed; roll it back");
            }
            connection.commit();
            committed = true;
        } catch (SQLException exception) {
            failure = exception;
            rollbackQuietly(connection, started, exception);
            throw translate(exception);
        } catch (RuntimeException | Error exception) {
            failure = exception;
            rollbackQuietly(connection, started, exception);
            throw exception;
        } finally {
            if (tx != null) tx.active = false;
            if (connection != null) {
                try { connection.close(); }
                catch (SQLException close) {
                    if (failure != null) failure.addSuppressed(close);
                    else if (!committed) throw translate(close);
                    else System.getLogger(Db.class.getName()).log(System.Logger.Level.WARNING,
                            "Vev committed, but connection cleanup failed", close);
                }
            }
            if (listener != null) {
                try { listener.onEnd(listenerToken, committed, failure); }
                catch (RuntimeException listenerError) {
                    if (failure != null) failure.addSuppressed(listenerError);
                    else System.getLogger(Db.class.getName()).log(System.Logger.Level.WARNING,
                            "Vev transaction listener failed", listenerError);
                }
            }
            if (!committed && tx != null && tx.afterCompletion != null) {
                try {
                    runCallbacksOutsideTransaction(tx, false);
                } catch (RuntimeException completionError) {
                    if (failure != null) failure.addSuppressed(completionError);
                    else throw completionError;
                }
            }
        }
        // Connection is now closed and returned to the pool; run post-commit hooks outside any transaction.
        if (tx != null && (tx.afterCommit != null || tx.afterCompletion != null)) {
            runCallbacksOutsideTransaction(tx, true);
        }
        return result;
    }

    private static void rollbackQuietly(Connection connection, boolean started, Throwable original) {
        if (connection == null || !started) return;
        try { connection.rollback(); }
        catch (SQLException rollback) { original.addSuppressed(rollback); }
    }

    private static void runCallbacksOutsideTransaction(Session.ActiveTx tx, boolean committed) {
        if (tx.afterCommit == null && tx.afterCompletion == null) return;
        ScopedValue.where(CURRENT_TX, null).where(CURRENT_TENANT, 0).run(() -> {
            try {
                if (committed) tx.runAfterCommit();
            } finally {
                tx.runAfterCompletion();
            }
        });
    }
}
