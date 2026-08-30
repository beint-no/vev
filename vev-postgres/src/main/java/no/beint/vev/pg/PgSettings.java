package no.beint.vev.pg;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded fail-closed timeouts applied to every PostgreSQL operation and connection.
 *
 * <p>All values use whole-millisecond precision. The statement timeout is from one millisecond through five minutes;
 * the transaction timeout must be greater and no more than ten minutes; the network timeout must be greater again
 * and no more than ten minutes.</p>
 *
 * @param statementTimeout server-side statement and lock timeout
 * @param transactionTimeout server-side timeout for the complete transaction
 * @param networkTimeout JDBC network timeout for the checked-out connection
 */
public record PgSettings(
        Duration statementTimeout,
        Duration transactionTimeout,
        Duration networkTimeout) {
    /** Conservative production defaults: 30-second statements, two-minute transactions, and three-minute network I/O. */
    public static final PgSettings SAFE_DEFAULTS = new PgSettings(
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(3));

    /**
     * Validates and creates a timeout policy.
     *
     * @param statementTimeout server-side statement and lock timeout
     * @param transactionTimeout server-side timeout for the complete transaction
     * @param networkTimeout JDBC network timeout for the checked-out connection
     */
    public PgSettings {
        statementTimeout = Objects.requireNonNull(statementTimeout, "statementTimeout");
        transactionTimeout = Objects.requireNonNull(transactionTimeout, "transactionTimeout");
        networkTimeout = Objects.requireNonNull(networkTimeout, "networkTimeout");
        if (!statementTimeout.equals(Duration.ofMillis(statementTimeout.toMillis()))
                || !transactionTimeout.equals(Duration.ofMillis(transactionTimeout.toMillis()))
                || !networkTimeout.equals(Duration.ofMillis(networkTimeout.toMillis()))) {
            throw new IllegalArgumentException("PostgreSQL safety timeouts must use whole-millisecond precision");
        }
        if (statementTimeout.compareTo(Duration.ofMillis(1)) < 0 || statementTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Statement timeout must be between 1 millisecond and 5 minutes");
        }
        if (transactionTimeout.compareTo(statementTimeout) <= 0
                || transactionTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(
                    "Transaction timeout must be greater than the statement timeout and at most 10 minutes");
        }
        if (networkTimeout.compareTo(transactionTimeout) <= 0
                || networkTimeout.compareTo(Duration.ofMinutes(10)) > 0
                || networkTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Network timeout must be greater than the transaction timeout and at most 10 minutes");
        }
    }
}
