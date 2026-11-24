package no.beint.vev;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;

/** Unchecked wrapper around a PostgreSQL {@link SQLException} with SQLSTATE helpers. */
public final class VevSqlException extends VevException {
    private static final long serialVersionUID = 1L;
    private static final Set<String> TRANSIENT_SQL_STATES = Set.of("40001", "40P01", "55P03", "57014");

    private final SQLException sqlException;

    public VevSqlException(SQLException cause) {
        super(Objects.requireNonNull(cause, "cause").getMessage(), cause);
        this.sqlException = cause;
    }

    public SQLException sqlException() { return sqlException; }

    public String sqlState() { return sqlException.getSQLState(); }

    public boolean isUniqueViolation() { return "23505".equals(sqlState()); }

    public boolean isForeignKeyViolation() { return "23503".equals(sqlState()); }

    public boolean isNotNullViolation() { return "23502".equals(sqlState()); }

    public boolean isCheckViolation() { return "23514".equals(sqlState()); }

    public boolean isIntegrityViolation() {
        String state = sqlState();
        return state != null && state.startsWith("23");
    }

    public boolean isSerializationFailure() { return "40001".equals(sqlState()); }

    public boolean isDeadlock() { return "40P01".equals(sqlState()); }

    public boolean isLockNotAvailable() { return "55P03".equals(sqlState()); }

    public boolean isQueryCanceled() { return "57014".equals(sqlState()); }

    public boolean isTransient() {
        String state = sqlState();
        return state != null && (TRANSIENT_SQL_STATES.contains(state) || state.startsWith("08"));
    }

    /** Returns the first {@link SQLException} in the cause chain, or {@code null}. */
    public static SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 64; depth++) {
            if (current instanceof SQLException sql) return sql;
            current = current.getCause();
        }
        return null;
    }

    public static boolean isUniqueViolation(Throwable throwable) {
        SQLException sql = findSqlException(throwable);
        return sql != null && "23505".equals(sql.getSQLState());
    }

    public static boolean isForeignKeyViolation(Throwable throwable) {
        SQLException sql = findSqlException(throwable);
        return sql != null && "23503".equals(sql.getSQLState());
    }

    public static boolean isIntegrityViolation(Throwable throwable) {
        SQLException sql = findSqlException(throwable);
        return sql != null && sql.getSQLState() != null && sql.getSQLState().startsWith("23");
    }

    public static boolean isTransient(Throwable throwable) {
        SQLException sql = findSqlException(throwable);
        if (sql == null) return false;
        String state = sql.getSQLState();
        return state != null && (TRANSIENT_SQL_STATES.contains(state) || state.startsWith("08"));
    }

    public static boolean isDatabaseFailure(Throwable throwable) {
        return findSqlException(throwable) != null;
    }
}
