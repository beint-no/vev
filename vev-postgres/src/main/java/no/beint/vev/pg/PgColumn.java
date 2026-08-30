package no.beint.vev.pg;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable generated metadata for one verified PostgreSQL column.
 *
 * @param name safe unquoted PostgreSQL identifier
 * @param codec standard Vev codec for the column value
 * @param nullable whether the value column accepts {@code null}; identity, tenant, and version columns never do
 * @param role structural role of the column in its entity plan
 * @param maximumLength maximum Unicode code points for a string column, or zero for other codecs
 * @param numericPrecision precision for a decimal column, or zero for other codecs
 * @param numericScale exact scale for a decimal column, or zero for other codecs
 */
public record PgColumn(
        String name,
        PgCodec<?> codec,
        boolean nullable,
        Role role,
        int maximumLength,
        int numericPrecision,
        int numericScale) {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final LocalDate MINIMUM_DATE = LocalDate.of(1, 1, 1);
    private static final LocalDate MAXIMUM_DATE = LocalDate.of(9_999, 12, 31);
    private static final LocalDateTime MINIMUM_DATE_TIME = MINIMUM_DATE.atStartOfDay();
    private static final LocalDateTime MAXIMUM_DATE_TIME =
            MAXIMUM_DATE.atTime(23, 59, 59, 999_999_000);
    private static final Instant MINIMUM_INSTANT = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAXIMUM_INSTANT = Instant.parse("9999-12-31T23:59:59.999999Z");

    /**
     * Validates and creates complete generated column metadata.
     *
     * @param name safe unquoted PostgreSQL identifier
     * @param codec standard Vev codec for the column value
     * @param nullable whether the value column accepts {@code null}
     * @param role structural role of the column
     * @param maximumLength maximum Unicode code points for a string column, or zero
     * @param numericPrecision precision for a decimal column, or zero
     * @param numericScale exact scale for a decimal column, or zero
     */
    public PgColumn {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsafe generated PostgreSQL column identifier");
        }
        codec = Objects.requireNonNull(codec, "codec");
        role = Objects.requireNonNull(role, "role");
        if ((role == Role.ID || role == Role.TENANT || role == Role.VERSION) && nullable) {
            throw new IllegalArgumentException(role + " columns must be non-null");
        }
        if (codec == PgCodecs.STRING) {
            if (maximumLength < 1 || maximumLength > 65_535) {
                throw new IllegalArgumentException("String columns require a maximum length from 1 through 65535");
            }
        } else if (maximumLength != 0) {
            throw new IllegalArgumentException("Only String columns may declare a maximum length");
        }
        if (codec == PgCodecs.BIG_DECIMAL) {
            if (numericPrecision < 1 || numericPrecision > 128
                    || numericScale < 0 || numericScale > numericPrecision) {
                throw new IllegalArgumentException("BigDecimal columns require precision 1 through 128 and a valid scale");
            }
        } else if (numericPrecision != 0 || numericScale != 0) {
            throw new IllegalArgumentException("Only BigDecimal columns may declare numeric precision and scale");
        }
    }

    /**
     * Creates column metadata with a 255-code-point string bound or decimal precision 38 and scale 2.
     *
     * @param name safe unquoted PostgreSQL identifier
     * @param codec standard Vev codec for the column value
     * @param nullable whether the value column accepts {@code null}
     * @param role structural role of the column
     */
    public PgColumn(
            String name,
            PgCodec<?> codec,
            boolean nullable,
            Role role) {
        this(
                name,
                codec,
                nullable,
                role,
                codec == PgCodecs.STRING ? 255 : 0,
                codec == PgCodecs.BIG_DECIMAL ? 38 : 0,
                codec == PgCodecs.BIG_DECIMAL ? 2 : 0);
    }

    int expectedTypeModifier() {
        if (codec == PgCodecs.STRING) {
            return Math.addExact(maximumLength, 4);
        }
        if (codec == PgCodecs.BIG_DECIMAL) {
            return Math.addExact((numericPrecision << 16) | numericScale, 4);
        }
        return -1;
    }

    long maximumRetainedBytes() {
        if (codec == PgCodecs.STRING) {
            return Math.addExact(64L, Math.multiplyExact(4L, maximumLength));
        }
        if (codec == PgCodecs.BIG_DECIMAL) {
            return Math.addExact(64L, Math.multiplyExact(2L, numericPrecision));
        }
        return 64L;
    }

    void validateValue(Object value) {
        if (value == null) {
            if (!nullable) {
                throw new IllegalArgumentException(name + " must not be null");
            }
            return;
        }
        if (!codec.javaType().isInstance(value)) {
            throw new IllegalArgumentException(name + " does not match its generated PostgreSQL codec");
        }
        if (value instanceof String text) {
            requireWellFormedUnicode(text);
            if (text.codePointCount(0, text.length()) > maximumLength) {
                throw new IllegalArgumentException(name + " exceeds its generated character bound");
            }
        } else if (value instanceof BigDecimal decimal
                && (decimal.precision() > numericPrecision || decimal.scale() != numericScale)) {
            throw new IllegalArgumentException(name + " does not match its generated numeric precision and scale");
        } else if (value instanceof LocalDate date
                && (date.isBefore(MINIMUM_DATE) || date.isAfter(MAXIMUM_DATE))) {
            throw new IllegalArgumentException(name + " is outside Vev's finite ISO date range");
        } else if (value instanceof Instant instant
                && (instant.getNano() % 1_000 != 0
                    || instant.isBefore(MINIMUM_INSTANT)
                    || instant.isAfter(MAXIMUM_INSTANT))) {
            throw new IllegalArgumentException(name + " is outside Vev's finite microsecond instant range");
        } else if (value instanceof LocalDateTime dateTime
                && (dateTime.getNano() % 1_000 != 0
                    || dateTime.isBefore(MINIMUM_DATE_TIME)
                    || dateTime.isAfter(MAXIMUM_DATE_TIME))) {
            throw new IllegalArgumentException(name + " is outside Vev's finite microsecond timestamp range");
        }
    }

    private static void requireWellFormedUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\0') {
                throw new IllegalArgumentException("String values must not contain U+0000");
            } else if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("String values must contain well-formed Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("String values must contain well-formed Unicode");
            }
        }
    }

    /** Structural role used to validate and compile an entity plan. */
    public enum Role {
        /** Primary-key column. */
        ID,
        /** Tenant-isolation column. */
        TENANT,
        /** Optimistic-version column. */
        VERSION,
        /** Ordinary entity value column. */
        VALUE
    }
}
