package no.beint.vev.pg;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.function.Function;

/**
 * Closed PostgreSQL/JDBC representation of one supported Java value type.
 *
 * <p>Codec instances are supplied by {@link PgCodecs}; Vev does not accept application-defined codecs in a
 * {@link PgModel}. The type is public so compile-time generated plans can carry strongly typed codec metadata.</p>
 *
 * @param <T> exact boxed Java value type
 */
public final class PgCodec<T> {
    private final Class<T> javaType;
    private final String databaseType;
    private final String sqlType;
    private final String jdbcType;
    private final SqlReader<T> reader;
    private final SqlBinder<T> binder;
    private final Function<T, Object> arrayElement;

    PgCodec(Class<T> javaType, String databaseType, SqlReader<T> reader, SqlBinder<T> binder) {
        this(javaType, databaseType, reader, binder, value -> value);
    }

    PgCodec(
            Class<T> javaType,
            String databaseType,
            SqlReader<T> reader,
            SqlBinder<T> binder,
            Function<T, Object> arrayElement) {
        this.javaType = Objects.requireNonNull(javaType, "javaType");
        this.databaseType = Objects.requireNonNull(databaseType, "databaseType");
        String catalogType = switch (databaseType) {
            case "boolean" -> "bool";
            case "smallint" -> "int2";
            case "integer" -> "int4";
            case "bigint" -> "int8";
            case "character varying" -> "varchar";
            default -> databaseType;
        };
        this.sqlType = "\"pg_catalog\".\"" + catalogType + "\"";
        this.jdbcType = "pg_catalog." + catalogType;
        this.reader = Objects.requireNonNull(reader, "reader");
        this.binder = Objects.requireNonNull(binder, "binder");
        this.arrayElement = Objects.requireNonNull(arrayElement, "arrayElement");
    }

    /**
     * Returns the Java type accepted and produced by this codec.
     *
     * @return exact boxed Java value type
     */
    public Class<T> javaType() {
        return javaType;
    }

    /**
     * Returns the canonical PostgreSQL catalog type name expected in verified schemas.
     *
     * @return unqualified canonical PostgreSQL type name
     */
    public String databaseType() {
        return databaseType;
    }

    String sqlType() {
        return sqlType;
    }

    String jdbcType() {
        return jdbcType;
    }

    T read(ResultSet resultSet, int index) throws SQLException {
        T value = reader.read(resultSet, index);
        return resultSet.wasNull() ? null : value;
    }

    void bind(PreparedStatement statement, int index, T value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NULL);
        } else {
            binder.bind(statement, index, value);
        }
    }

    Object arrayElement(Object value) {
        if (value == null) {
            return null;
        }
        if (value.getClass() != javaType) {
            throw new IllegalArgumentException("Array value does not match the generated PostgreSQL codec");
        }
        return arrayElement.apply(javaType.cast(value));
    }

    @FunctionalInterface
    interface SqlReader<T> {
        T read(ResultSet resultSet, int index) throws SQLException;
    }

    @FunctionalInterface
    interface SqlBinder<T> {
        void bind(PreparedStatement statement, int index, T value) throws SQLException;
    }
}
