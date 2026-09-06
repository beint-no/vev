package no.beint.vev.pg;

/**
 * Declared PostgreSQL 18 check constraint. The expression is schema metadata, never executable SQL in Vev.
 *
 * @param name explicit constraint name
 * @param expression exact {@code pg_get_expr(conbin, conrelid, false)} output
 */
public record PgCheck(String name, String expression) {
    /** Maximum number of declared checks on one entity. */
    public static final int MAXIMUM_PER_ENTITY = 32;
    /** Maximum characters in one deparsed expression. */
    public static final int MAXIMUM_EXPRESSION_LENGTH = 4096;
    /** Maximum total expression characters retained by one closed model. */
    public static final int MAXIMUM_MODEL_CHARACTERS = 16 * 1024 * 1024;

    /**
     * Captures bounded, exact schema metadata.
     *
     * @param name safe unquoted identifier
     * @param expression PostgreSQL's deparsed expression, including its parentheses and casts
     */
    public PgCheck {
        if (name == null || !name.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Unsafe generated PostgreSQL check-constraint identifier");
        }
        if (expression == null || expression.isBlank() || expression.length() > MAXIMUM_EXPRESSION_LENGTH
                || expression.indexOf('\0') >= 0 || !wellFormedUnicode(expression)) {
            throw new IllegalArgumentException("Check expression must be bounded, nonempty Unicode schema metadata");
        }
    }

    private static boolean wellFormedUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(character)) return false;
        }
        return true;
    }
}
