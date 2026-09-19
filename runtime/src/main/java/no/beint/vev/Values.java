package no.beint.vev;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Strict JDBC array decoding, retaining nullable elements and releasing JDBC resources. */
public final class Values {
    private Values() {}

    public static <T> List<T> array(ResultSet result, int position, Class<T> elementType) throws SQLException {
        var array = result.getArray(position);
        if (array == null) return null;
        try {
            Object[] values = (Object[]) array.getArray();
            List<T> decoded = new ArrayList<>(values.length);
            for (Object value : values) {
                if (value != null && !elementType.isInstance(value)) throw new java.sql.SQLDataException("Vev requires a one-dimensional SQL array with the declared element type");
                decoded.add(elementType.cast(value));
            }
            return Collections.unmodifiableList(decoded);
        } finally { array.free(); }
    }
}
