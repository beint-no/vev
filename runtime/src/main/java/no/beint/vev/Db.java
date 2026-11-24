package no.beint.vev;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;

/**
 * Re-entrant, capability-typed PostgreSQL transactions with savepoints, isolation/timeout controls,
 * and before/after-commit lifecycle hooks.
 *
 * <p>Outermost calls own the JDBC connection, commit, rollback, and close. Nested calls join the
 * enclosing transaction on the same connection while rejecting read-to-write escalation,
 * isolation elevation, and cross-tenant switching. {@code readNew} / {@code writeNew} open an
 * independent transaction; {@code writeIsolated} rejects any enclosing transaction;
 * {@code savepoint} creates a PostgreSQL savepoint inside an active write transaction that rolls
 * back SQL/runtime errors and nested lifecycle callbacks without poisoning the outer transaction.
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
    private final long queryTimeoutMillis;
    private final int queryTimeoutSeconds;
    private final boolean hasTimeoutOverride;
    private final Supplier<Integer> tenantSupplier;
    private final TransactionListener listener;
    private final UnaryOperator<Runnable> callbackDecorator;
    private final Function<SQLException, RuntimeException> exceptionTranslator;
    private final AtomicBoolean verified;

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
        this.queryTimeoutMillis = Session.timeoutMillis(builder.queryTimeout);
        this.queryTimeoutSeconds = Session.millisToSeconds(this.queryTimeoutMillis);
        this.hasTimeoutOverride = false;
        this.tenantSupplier = builder.tenantSupplier;
        this.listener = builder.listener;
        this.callbackDecorator = builder.callbackDecorator;
        this.exceptionTranslator = builder.exceptionTranslator;
        this.verified = new AtomicBoolean();
    }

    private Db(Db parent, Duration queryTimeout) {
        this.source = parent.source;
        this.backend = parent.backend;
        this.isolation = parent.isolation;
        this.queryTimeoutMillis = Session.timeoutMillis(queryTimeout);
        this.queryTimeoutSeconds = Session.millisToSeconds(this.queryTimeoutMillis);
        this.hasTimeoutOverride = true;
        this.tenantSupplier = parent.tenantSupplier;
        this.listener = parent.listener;
        this.callbackDecorator = parent.callbackDecorator;
        this.exceptionTranslator = parent.exceptionTranslator;
        this.verified = parent.verified;
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
        private Supplier<Integer> tenantSupplier;
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

        public Builder tenantSupplier(Supplier<Integer> tenantSupplier) {
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

    /**
     * Returns a lightweight view of this {@code Db} that applies {@code queryTimeout} to both
     * PostgreSQL {@code statement_timeout} and JDBC statement timeouts. Passing {@code null}
     * disables the timeout for operations executed through the returned view. When joining an
     * enclosing transaction, the previous timeout is restored when the nested call completes.
     */
    public Db withTimeout(Duration queryTimeout) {
        return new Db(this, queryTimeout);
    }

    // ---- Global transactions ----

    public <R> R read(ReadWork<R> work) {
        Objects.requireNonNull(work, "work");
        return execute(true, false, false, DEFAULT_ISOLATION, 0, tx -> {
            var scope = new Session.GlobalRead(tx);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R readNew(ReadWork<R> work) {
        Objects.requireNonNull(work, "work");
        return execute(true, true, false, DEFAULT_ISOLATION, 0, tx -> {
            var scope = new Session.GlobalRead(tx);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R readRepeatable(ReadWork<R> work) {
        Objects.requireNonNull(work, "work");
        return execute(true, false, false, Connection.TRANSACTION_REPEATABLE_READ, 0, tx -> {
            var scope = new Session.GlobalRead(tx);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R write(WriteWork<R> work) {
        Objects.requireNonNull(work, "work");
        return execute(false, false, false, DEFAULT_ISOLATION, 0, tx -> {
            var scope = new Session.GlobalWrite(tx);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R writeNew(WriteWork<R> work) {
        Objects.requireNonNull(work, "work");
        return execute(false, true, false, DEFAULT_ISOLATION, 0, tx -> {
            var scope = new Session.GlobalWrite(tx);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R writeIsolated(WriteWork<R> work) {
        Objects.requireNonNull(work, "work");
        return execute(false, false, true, DEFAULT_ISOLATION, 0, tx -> {
            var scope = new Session.GlobalWrite(tx);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R writeSerializable(WriteWork<R> work) {
        Objects.requireNonNull(work, "work");
        return execute(false, false, false, Connection.TRANSACTION_SERIALIZABLE, 0, tx -> {
            var scope = new Session.GlobalWrite(tx);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R savepoint(WriteWork<R> work) {
        Objects.requireNonNull(work, "work");
        return executeSavepoint(0, tx -> {
            var scope = new Session.GlobalWrite(tx);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    // ---- Explicit-tenant transactions ----

    public <R> R read(int tenantId, TenantReadWork<R> work) {
        Session.requireTenant(tenantId);
        Objects.requireNonNull(work, "work");
        return execute(true, false, false, DEFAULT_ISOLATION, tenantId, tx -> {
            var scope = new Session.TenantRead(tx, tenantId);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R readNew(int tenantId, TenantReadWork<R> work) {
        Session.requireTenant(tenantId);
        Objects.requireNonNull(work, "work");
        return execute(true, true, false, DEFAULT_ISOLATION, tenantId, tx -> {
            var scope = new Session.TenantRead(tx, tenantId);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R readRepeatable(int tenantId, TenantReadWork<R> work) {
        Session.requireTenant(tenantId);
        Objects.requireNonNull(work, "work");
        return execute(true, false, false, Connection.TRANSACTION_REPEATABLE_READ, tenantId, tx -> {
            var scope = new Session.TenantRead(tx, tenantId);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R write(int tenantId, TenantWriteWork<R> work) {
        Session.requireTenant(tenantId);
        Objects.requireNonNull(work, "work");
        return execute(false, false, false, DEFAULT_ISOLATION, tenantId, tx -> {
            var scope = new Session.TenantWrite(tx, tenantId);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R writeNew(int tenantId, TenantWriteWork<R> work) {
        Session.requireTenant(tenantId);
        Objects.requireNonNull(work, "work");
        return execute(false, true, false, DEFAULT_ISOLATION, tenantId, tx -> {
            var scope = new Session.TenantWrite(tx, tenantId);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R writeIsolated(int tenantId, TenantWriteWork<R> work) {
        Session.requireTenant(tenantId);
        Objects.requireNonNull(work, "work");
        return execute(false, false, true, DEFAULT_ISOLATION, tenantId, tx -> {
            var scope = new Session.TenantWrite(tx, tenantId);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R writeSerializable(int tenantId, TenantWriteWork<R> work) {
        Session.requireTenant(tenantId);
        Objects.requireNonNull(work, "work");
        return execute(false, false, false, Connection.TRANSACTION_SERIALIZABLE, tenantId, tx -> {
            var scope = new Session.TenantWrite(tx, tenantId);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    public <R> R savepoint(int tenantId, TenantWriteWork<R> work) {
        Session.requireTenant(tenantId);
        Objects.requireNonNull(work, "work");
        return executeSavepoint(tenantId, tx -> {
            var scope = new Session.TenantWrite(tx, tenantId);
            try { return work.run(scope); } finally { scope.deactivate(); }
        });
    }

    // ---- Current-tenant transactions (uses enclosing tenant scope if active, else tenantSupplier) ----

    public <R> R tenantRead(TenantReadWork<R> work) {
        return read(resolveTenantId(), work);
    }

    public <R> R tenantReadNew(TenantReadWork<R> work) {
        return readNew(resolveTenantId(), work);
    }

    public <R> R tenantReadRepeatable(TenantReadWork<R> work) {
        return readRepeatable(resolveTenantId(), work);
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

    public <R> R tenantWriteSerializable(TenantWriteWork<R> work) {
        return writeSerializable(resolveTenantId(), work);
    }

    public <R> R tenantSavepoint(TenantWriteWork<R> work) {
        return savepoint(resolveTenantId(), work);
    }

    /** Returns the active enclosing tenant identifier if inside a tenant scope, otherwise queries the configured tenantSupplier. */
    public int currentTenantId() {
        return resolveTenantId();
    }

    /** Returns the active enclosing tenant identifier or the configured tenantSupplier's value, or {@code null} when none is bound. */
    public Integer currentTenantIdOrNull() {
        int enclosing = currentTenantOrZero();
        if (enclosing > 0) return enclosing;
        if (tenantSupplier == null) return null;
        Integer value = tenantSupplier.get();
        return value != null ? Session.requireTenant(value) : null;
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

    /** Verifies that the caller is inside an active write transaction. */
    public void requireWriteTransaction() {
        if (!inWriteTransaction()) {
            throw new VevSessionException("Expected an active write transaction");
        }
    }

    public void beforeCommit(Runnable action) {
        Objects.requireNonNull(action, "action");
        Runnable tenantBound = bindTenant(currentTenantOrZero(), action);
        Session.ActiveTx tx = currentTxOrNull();
        if (tx != null) {
            tx.beforeCommit(tenantBound);
            return;
        }
        if (backend != null && backend.isTransactionActive()) {
            if (backend.isCurrentTransactionReadOnly()) {
                throw new VevSessionException("beforeCommit requires a write transaction");
            }
            backend.beforeCommit(tenantBound);
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

    static Runnable bindTenant(int tenantId, Runnable action) {
        if (tenantId <= 0) return action;
        return () -> ScopedValue.where(CURRENT_TENANT, tenantId).run(action);
    }

    // ---- Execution core ----

    @FunctionalInterface private interface InternalWork<R> { R run(Session.ActiveTx tx) throws SQLException; }

    private int resolveTenantId() {
        int enclosing = currentTenantOrZero();
        if (enclosing > 0) return enclosing;
        if (tenantSupplier == null) {
            throw new VevSessionException("No tenantSupplier configured on Db; pass an explicit tenantId or configure tenantSupplier");
        }
        Integer tenantId = tenantSupplier.get();
        if (tenantId == null) {
            throw new VevSessionException("Missing tenant identifier");
        }
        return Session.requireTenant(tenantId);
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

    private <R> R execute(boolean readOnly, boolean requiresNew, boolean isolated, int requestedIsolation, int tenantId, InternalWork<R> work) {
        if (isolated && inTransaction()) {
            throw new VevSessionException("db.writeIsolated must not run inside another transaction");
        }
        Session.ActiveTx enclosing = currentTxOrNull();
        if (enclosing != null && !requiresNew) {
            return joinEnclosing(enclosing, readOnly, requestedIsolation, tenantId, work);
        }
        int effectiveIsolation = requestedIsolation != DEFAULT_ISOLATION ? requestedIsolation : this.isolation;
        if (backend != null) {
            return executeWithBackend(readOnly, requiresNew, effectiveIsolation, tenantId, work);
        }
        return executeWithDataSource(readOnly, effectiveIsolation, tenantId, work);
    }

    private <R> R joinEnclosing(Session.ActiveTx enclosing, boolean readOnly, int requestedIsolation, int tenantId, InternalWork<R> work) {
        enclosing.check();
        if (!readOnly && enclosing.readOnly) {
            throw new VevSessionException("Cannot start a write transaction inside a read-only transaction");
        }
        int effectiveIsolation = requestedIsolation != DEFAULT_ISOLATION ? requestedIsolation : this.isolation;
        if (effectiveIsolation != DEFAULT_ISOLATION && isolationRank(effectiveIsolation) > isolationRank(enclosing.isolation)) {
            throw new VevSessionException("Cannot raise transaction isolation inside an active transaction; "
                    + "start the outermost transaction at the higher isolation level or use readNew/writeNew");
        }
        int enclosingTenant = currentTenantOrZero();
        if (tenantId > 0 && enclosingTenant > 0 && tenantId != enclosingTenant) {
            throw new VevSessionException("Cannot switch tenant from " + enclosingTenant + " to " + tenantId
                    + " inside an active tenant transaction; use writeNew for a separate tenant transaction");
        }
        long previousMillis = enclosing.queryTimeoutMillis;
        int previousSeconds = enclosing.queryTimeoutSeconds;
        boolean timeoutChanged = hasTimeoutOverride && queryTimeoutMillis != previousMillis;
        Throwable failure = null;
        try {
            if (timeoutChanged) {
                setLocalStatementTimeout(enclosing.connection, queryTimeoutMillis);
                enclosing.queryTimeoutMillis = queryTimeoutMillis;
                enclosing.queryTimeoutSeconds = queryTimeoutSeconds;
            }
            if (tenantId > 0 && enclosingTenant == 0) {
                return ScopedValue.where(CURRENT_TENANT, tenantId).call(() -> work.run(enclosing));
            }
            return work.run(enclosing);
        } catch (SQLException sql) {
            failure = sql;
            enclosing.markFailed();
            throw translate(sql);
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            if (timeoutChanged) {
                enclosing.queryTimeoutMillis = previousMillis;
                enclosing.queryTimeoutSeconds = previousSeconds;
                if (enclosing.active && !enclosing.failed) {
                    try {
                        setLocalStatementTimeout(enclosing.connection, previousMillis);
                    } catch (SQLException restoreError) {
                        enclosing.markFailed();
                        if (failure != null) failure.addSuppressed(restoreError);
                        else throw translate(restoreError);
                    }
                }
            }
        }
    }

    private <R> R executeSavepoint(int tenantId, InternalWork<R> work) {
        Session.ActiveTx enclosing = currentTxOrNull();
        if (enclosing == null) {
            throw new VevSessionException("savepoint requires an enclosing write transaction");
        }
        enclosing.check();
        if (enclosing.readOnly) {
            throw new VevSessionException("savepoint requires a write transaction");
        }
        int enclosingTenant = currentTenantOrZero();
        if (tenantId > 0 && enclosingTenant > 0 && tenantId != enclosingTenant) {
            throw new VevSessionException("Cannot switch tenant from " + enclosingTenant + " to " + tenantId
                    + " inside an active tenant transaction; use writeNew for a separate tenant transaction");
        }
        int beforeCommitSize = enclosing.beforeCommit != null ? enclosing.beforeCommit.size() : 0;
        int afterCommitSize = enclosing.afterCommit != null ? enclosing.afterCommit.size() : 0;
        int afterCompletionSize = enclosing.afterCompletion != null ? enclosing.afterCompletion.size() : 0;
        Savepoint savepoint;
        try {
            savepoint = enclosing.connection.setSavepoint();
        } catch (SQLException failure) {
            enclosing.markFailed();
            throw translate(failure);
        }
        try {
            R result = (tenantId > 0 && enclosingTenant == 0)
                    ? ScopedValue.where(CURRENT_TENANT, tenantId).call(() -> work.run(enclosing))
                    : work.run(enclosing);
            if (enclosing.failed) {
                throw new VevSessionException("Vev savepoint failed; rolled back to savepoint");
            }
            enclosing.connection.releaseSavepoint(savepoint);
            return result;
        } catch (SQLException | RuntimeException | Error failure) {
            enclosing.truncateCallbacks(beforeCommitSize, afterCommitSize, afterCompletionSize);
            try {
                enclosing.connection.rollback(savepoint);
                enclosing.connection.releaseSavepoint(savepoint);
                enclosing.failed = false;
            } catch (SQLException rollbackFailure) {
                enclosing.markFailed();
                failure.addSuppressed(rollbackFailure);
            }
            if (failure instanceof SQLException sql) throw translate(sql);
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw (Error) failure;
        }
    }

    private <R> R executeWithBackend(boolean readOnly, boolean requiresNew, int effectiveIsolation, int tenantId, InternalWork<R> work) {
        if (!requiresNew && !readOnly && backend.isTransactionActive() && backend.isCurrentTransactionReadOnly()) {
            throw new VevSessionException("Cannot start a write transaction inside a read-only transaction");
        }
        try {
            return backend.execute(readOnly, requiresNew, (connection, remainingTimeout) -> {
                if (connection.getAutoCommit()) {
                    throw new VevSessionException("Vev requires an explicit transaction");
                }
                long timeoutMs = hasTimeoutOverride
                        ? queryTimeoutMillis
                        : (remainingTimeout != null ? Session.timeoutMillis(remainingTimeout) : queryTimeoutMillis);
                Session.ActiveTx tx = new Session.ActiveTx(connection, readOnly, effectiveIsolation, timeoutMs, backend, callbackDecorator);
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

    private <R> R executeWithDataSource(boolean readOnly, int effectiveIsolation, int tenantId, InternalWork<R> work) {
        Object listenerToken = listener != null ? listener.onBegin(readOnly, tenantId) : null;
        Connection connection = null;
        Throwable failure = null;
        boolean committed = false;
        boolean started = false;
        Session.ActiveTx tx = null;
        R result;
        try {
            connection = source.getConnection();
            if (!verified.get()) {
                Session.requirePostgres(connection);
                verified.set(true);
            }
            if (connection.getAutoCommit()) connection.setAutoCommit(false);
            started = true;
            String setupSql = transactionSetupSql(readOnly && !connection.isReadOnly(), effectiveIsolation, queryTimeoutMillis, hasTimeoutOverride);
            if (setupSql != null) {
                // Transaction-scoped SET TRANSACTION / SET LOCAL avoid mutating pooled session state.
                try (Statement statement = connection.createStatement()) {
                    statement.execute(setupSql);
                }
            }
            Session.ActiveTx activeTx = new Session.ActiveTx(connection, readOnly, effectiveIsolation, queryTimeoutMillis, null, callbackDecorator);
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

    private static String transactionSetupSql(boolean setReadOnly, int isolation, long timeoutMillis, boolean hasTimeoutOverride) {
        String isolationClause = switch (isolation) {
            case Connection.TRANSACTION_READ_COMMITTED -> "ISOLATION LEVEL READ COMMITTED";
            case Connection.TRANSACTION_REPEATABLE_READ -> "ISOLATION LEVEL REPEATABLE READ";
            case Connection.TRANSACTION_SERIALIZABLE -> "ISOLATION LEVEL SERIALIZABLE";
            default -> null;
        };
        String txSql = null;
        if (isolationClause != null && setReadOnly) {
            txSql = "SET TRANSACTION " + isolationClause + ", READ ONLY";
        } else if (isolationClause != null) {
            txSql = "SET TRANSACTION " + isolationClause;
        } else if (setReadOnly) {
            txSql = "SET TRANSACTION READ ONLY";
        }
        String timeoutSql = (timeoutMillis > 0 || hasTimeoutOverride)
                ? "SET LOCAL statement_timeout = " + timeoutMillis
                : null;
        if (txSql != null && timeoutSql != null) return txSql + "; " + timeoutSql;
        return txSql != null ? txSql : timeoutSql;
    }

    private static void setLocalStatementTimeout(Connection connection, long timeoutMillis) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL statement_timeout = " + timeoutMillis);
        }
    }

    private static int isolationRank(int isolation) {
        return switch (isolation) {
            case Connection.TRANSACTION_REPEATABLE_READ -> 2;
            case Connection.TRANSACTION_SERIALIZABLE -> 3;
            default -> 1;
        };
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
