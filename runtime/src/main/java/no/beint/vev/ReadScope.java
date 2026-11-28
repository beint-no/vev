package no.beint.vev;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Capability proving an active read-only (or read-write) Vev transaction for global read queries. */
public sealed interface ReadScope permits WriteScope, TenantReadScope, Session.GlobalRead {
    boolean isReadOnly();

    /** Registers work to run once the enclosing transaction commits, outside the transaction. */
    void afterCommit(Runnable action);

    /** Registers work to run after the enclosing transaction finishes (commit or rollback), outside it. */
    void afterCompletion(Runnable action);

    /** Returns the active transaction connection for statement-local JDBC helpers. */
    Connection connection();

    /** Prepares a statement in this scope, applying the configured query timeout if any. */
    PreparedStatement prepare(String sql) throws SQLException;

    /** Prepares a statement PostgreSQL plans with each execution's parameter values. */
    PreparedStatement prepareCustomPlan(String sql) throws SQLException;

    /** Marks the enclosing transaction failed so subsequent work is rejected and the transaction rolls back. */
    void markFailed();
}
