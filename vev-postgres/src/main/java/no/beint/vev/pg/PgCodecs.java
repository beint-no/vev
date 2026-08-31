package no.beint.vev.pg;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
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
            BOOLEAN, INTEGER, LONG, SHORT, STRING, UUID, BIG_DECIMAL,
            LOCAL_DATE, LOCAL_DATE_TIME, INSTANT);

    private PgCodecs() {
    }

    static boolean isStandard(PgCodec<?> codec) {
        return STANDARD.contains(codec);
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
