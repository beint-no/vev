package no.beint.vev;

/**
 * Mandatory upper bound for a generated multi-row query.
 *
 * @param value maximum rows returned to application code
 */
public record QueryLimit(int value) {
    /** Largest supported application result page. */
    public static final int MAX_VALUE = 1_000;

    /**
     * Validates a mandatory query bound.
     *
     * @param value maximum rows returned to application code
     */
    public QueryLimit {
        if (value < 1 || value > MAX_VALUE) {
            throw new IllegalArgumentException("Query limit must be between 1 and " + MAX_VALUE);
        }
    }
}
