package no.beint.vev.pg;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Standard, non-extensible codecs accepted by Vev's PostgreSQL runtime and generated plans. */
public final class PgCodecs {
    /** Java {@link Boolean} mapped to PostgreSQL {@code boolean}. */
    public static final PgCodec<Boolean> BOOLEAN = codec(
            Boolean.class, "boolean", ResultSet::getBoolean, PreparedStatement::setBoolean);
    /** Java {@link Integer} mapped to PostgreSQL {@code integer}. */
    public static final PgCodec<Integer> INTEGER = codec(
            Integer.class, "integer", ResultSet::getInt, PreparedStatement::setInt);
    /** Java {@link Long} mapped to PostgreSQL {@code bigint}. */
    public static final PgCodec<Long> LONG = codec(
            Long.class, "bigint", ResultSet::getLong, PreparedStatement::setLong);
    /** Java {@link Short} mapped to PostgreSQL {@code smallint}. */
    public static final PgCodec<Short> SHORT = codec(
            Short.class, "smallint", ResultSet::getShort, PreparedStatement::setShort);
    /** Java {@link String} mapped to bounded PostgreSQL {@code character varying}. */
    public static final PgCodec<String> STRING = codec(
            String.class, "character varying", ResultSet::getString, PreparedStatement::setString);
    /** Code-point-bounded String mapped to PostgreSQL text with a required verified length constraint. */
    public static final PgCodec<String> TEXT = codec(
            String.class, "text", ResultSet::getString, PreparedStatement::setString);
    /** Immutable {@link no.beint.vev.Binary} mapped to byte-length-bounded PostgreSQL {@code bytea}. */
    public static final PgCodec<no.beint.vev.Binary> BINARY = codec(
            no.beint.vev.Binary.class, "bytea",
            (resultSet, index) -> {
                byte[] bytes = resultSet.getBytes(index);
                return bytes == null ? null : no.beint.vev.Binary.copyOf(bytes);
            },
            (statement, index, value) -> statement.setBinaryStream(index, value.openStream(), value.size()),
            no.beint.vev.Binary::toByteArray);
    /** Java {@link UUID} mapped to PostgreSQL {@code uuid}. */
    public static final PgCodec<UUID> UUID = objectCodec(UUID.class, "uuid");
    /** Java {@link BigDecimal} mapped to precision- and scale-bounded PostgreSQL {@code numeric}. */
    public static final PgCodec<BigDecimal> BIG_DECIMAL = codec(
            BigDecimal.class, "numeric", ResultSet::getBigDecimal, PreparedStatement::setBigDecimal);
    /** Java {@link LocalDate} mapped to PostgreSQL {@code date}. */
    public static final PgCodec<LocalDate> LOCAL_DATE = objectCodec(LocalDate.class, "date");
    /** Java {@link LocalDateTime} mapped to PostgreSQL {@code timestamp} without a time zone. */
    public static final PgCodec<LocalDateTime> LOCAL_DATE_TIME = objectCodec(LocalDateTime.class, "timestamp");
    /** Java {@link Instant} mapped to PostgreSQL {@code timestamptz} at microsecond precision. */
    public static final PgCodec<Instant> INSTANT = codec(
            Instant.class,
            "timestamptz",
            (resultSet, index) -> {
                java.time.OffsetDateTime value = resultSet.getObject(index, java.time.OffsetDateTime.class);
                return value == null ? null : value.toInstant();
            },
            (statement, index, value) -> statement.setObject(index, value.atOffset(java.time.ZoneOffset.UTC)),
            value -> value.atOffset(java.time.ZoneOffset.UTC));
    private static final Set<PgCodec<?>> STANDARD = Set.of(
            BOOLEAN, INTEGER, LONG, SHORT, STRING, TEXT, BINARY, UUID, BIG_DECIMAL,
            LOCAL_DATE, LOCAL_DATE_TIME, INSTANT);

    private PgCodecs() {
    }

    /**
     * Creates an exact enum-name codec for an ahead-of-time generated mapping.
     *
     * <p>Generated plans pass the enum's {@code values()} array once during initialization. Names are bound as
     * strings, never ordinals or {@code toString()} values. Unknown database names fail hydration.</p>
     *
     * @param javaType declared enum type
     * @param constants complete generated enum constant set
     * @param <E> enum type
     * @return immutable name codec without reflective lookup during hydration
     */
    public static <E extends Enum<E>> PgCodec<E> enumNames(Class<E> javaType, E[] constants) {
        Objects.requireNonNull(javaType, "javaType");
        Objects.requireNonNull(constants, "constants");
        if (!javaType.isEnum() || constants.length == 0 || constants.length > 1_024) {
            throw new IllegalArgumentException("An enum codec requires between 1 and 1024 declared constants");
        }
        Map<String, E> names = new HashMap<>();
        for (E constant : constants) {
            Objects.requireNonNull(constant, "constant");
            if (constant.getDeclaringClass() != javaType || names.putIfAbsent(constant.name(), constant) != null) {
                throw new IllegalArgumentException("Enum constants must be distinct members of the declared enum type");
            }
        }
        Map<String, E> values = Map.copyOf(names);
        return codec(javaType, "character varying", (resultSet, index) -> {
            String name = resultSet.getString(index);
            if (name == null) {
                return null;
            }
            E value = values.get(name);
            if (value == null) {
                throw new SQLException("Database enum name is outside the generated mapping for " + javaType.getName());
            }
            return value;
        }, (statement, index, value) -> statement.setString(index, value.name()), Enum::name);
    }

    static boolean isStandard(PgCodec<?> codec) {
        return STANDARD.contains(codec) || codec.javaType().isEnum();
    }

    private static <T> PgCodec<T> codec(
            Class<T> javaType,
            String arrayElementType,
            SqlReader<T> reader,
            SqlBinder<T> binder) {
        return new PgCodec<>(javaType, arrayElementType, reader::read, binder::bind);
    }

    private static <T> PgCodec<T> codec(
            Class<T> javaType,
            String arrayElementType,
            SqlReader<T> reader,
            SqlBinder<T> binder,
            java.util.function.Function<T, Object> arrayElement) {
        return new PgCodec<>(javaType, arrayElementType, reader::read, binder::bind, arrayElement);
    }

    private static <T> PgCodec<T> objectCodec(Class<T> javaType, String arrayElementType) {
        return codec(
                javaType,
                arrayElementType,
                (resultSet, index) -> resultSet.getObject(index, javaType),
                (statement, index, value) -> statement.setObject(index, value));
    }

    @FunctionalInterface
    private interface SqlReader<T> {
        T read(ResultSet resultSet, int index) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlBinder<T> {
        void bind(PreparedStatement statement, int index, T value) throws SQLException;
    }

}
