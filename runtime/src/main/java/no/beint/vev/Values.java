package no.beint.vev;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Strict JDBC scalar and array decoding, retaining nullable elements and releasing JDBC resources. */
public final class Values {
    private Values() {}

    public static boolean booleanValue(ResultSet result, int position, String message) throws SQLException {
        boolean value = result.getBoolean(position);
        if (result.wasNull()) throw new IllegalArgumentException(message);
        return value;
    }

    public static Boolean booleanOrNull(ResultSet result, int position) throws SQLException {
        boolean value = result.getBoolean(position);
        return result.wasNull() ? null : value;
    }

    public static short shortValue(ResultSet result, int position, String message) throws SQLException {
        short value = result.getShort(position);
        if (result.wasNull()) throw new IllegalArgumentException(message);
        return value;
    }

    public static Short shortOrNull(ResultSet result, int position) throws SQLException {
        short value = result.getShort(position);
        return result.wasNull() ? null : value;
    }

    public static int intValue(ResultSet result, int position, String message) throws SQLException {
        int value = result.getInt(position);
        if (result.wasNull()) throw new IllegalArgumentException(message);
        return value;
    }

    public static Integer intOrNull(ResultSet result, int position) throws SQLException {
        int value = result.getInt(position);
        return result.wasNull() ? null : value;
    }

    public static long longValue(ResultSet result, int position, String message) throws SQLException {
        long value = result.getLong(position);
        if (result.wasNull()) throw new IllegalArgumentException(message);
        return value;
    }

    public static Long longOrNull(ResultSet result, int position) throws SQLException {
        long value = result.getLong(position);
        return result.wasNull() ? null : value;
    }

    public static float floatValue(ResultSet result, int position, String message) throws SQLException {
        float value = result.getFloat(position);
        if (result.wasNull()) throw new IllegalArgumentException(message);
        return value;
    }

    public static Float floatOrNull(ResultSet result, int position) throws SQLException {
        float value = result.getFloat(position);
        return result.wasNull() ? null : value;
    }

    public static double doubleValue(ResultSet result, int position, String message) throws SQLException {
        double value = result.getDouble(position);
        if (result.wasNull()) throw new IllegalArgumentException(message);
        return value;
    }

    public static Double doubleOrNull(ResultSet result, int position) throws SQLException {
        double value = result.getDouble(position);
        return result.wasNull() ? null : value;
    }

    public static Instant instantValue(ResultSet result, int position, String message) throws SQLException {
        OffsetDateTime value = result.getObject(position, OffsetDateTime.class);
        if (value == null) throw new IllegalArgumentException(message);
        return value.toInstant();
    }

    public static Instant instantOrNull(ResultSet result, int position) throws SQLException {
        OffsetDateTime value = result.getObject(position, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public static <T> List<T> array(ResultSet result, int position, Class<T> elementType) throws SQLException {
        Array array = result.getArray(position);
        if (array == null) return null;
        try {
            if (isTemporal(elementType)) {
                return temporalArray(array, elementType);
            }
            Object[] values = (Object[]) array.getArray();
            if (values.length == 0) return List.of();
            List<T> decoded = new ArrayList<>(values.length);
            for (Object value : values) {
                if (value == null) {
                    decoded.add(null);
                    continue;
                }
                if (value.getClass().isArray()) {
                    throw new java.sql.SQLDataException("Vev requires a one-dimensional SQL array with the declared element type");
                }
                Object converted = value;
                if (elementType == String.class && value.getClass().getName().equals("org.postgresql.util.PGobject")) {
                    converted = value.toString();
                }
                if (!elementType.isInstance(converted)) {
                    throw new java.sql.SQLDataException("Vev requires a one-dimensional SQL array with the declared element type");
                }
                decoded.add(elementType.cast(converted));
            }
            return Collections.unmodifiableList(decoded);
        } finally { array.free(); }
    }

    private static boolean isTemporal(Class<?> elementType) {
        return elementType == LocalDate.class
                || elementType == LocalTime.class
                || elementType == LocalDateTime.class
                || elementType == Instant.class;
    }

    /** Uses Array.getResultSet() so pgjdbc decodes java.time values with full microsecond precision and no JVM timezone drift. */
    private static <T> List<T> temporalArray(Array array, Class<T> elementType) throws SQLException {
        List<T> decoded = null;
        try (ResultSet elements = array.getResultSet()) {
            while (elements.next()) {
                Object value;
                if (elementType == Instant.class) {
                    OffsetDateTime odt = elements.getObject(2, OffsetDateTime.class);
                    value = odt == null ? null : odt.toInstant();
                } else {
                    value = elements.getObject(2, elementType);
                }
                if (decoded == null) decoded = new ArrayList<>();
                decoded.add(elementType.cast(value));
            }
        }
        if (decoded == null) return List.of();
        return Collections.unmodifiableList(decoded);
    }
}
