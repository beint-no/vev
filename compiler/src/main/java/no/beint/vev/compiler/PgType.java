package no.beint.vev.compiler;

import java.nio.file.Path;
import java.util.Set;

record PgType(String postgres, String kotlin, int jdbc, PgType element) {
    /** Types whose text representation can carry a Kotlin enum constant name. */
    private static final Set<String> TEXTUAL = Set.of("text", "varchar", "bpchar", "name", "citext");
    /** Element types whose JDBC array decoding is verified; others must be cast in SQL. */
    private static final Set<String> ARRAY_ELEMENTS =
            Set.of("bool", "int2", "int4", "int8", "float4", "float8", "numeric", "text", "varchar", "bpchar", "uuid");

    static PgType of(Path source, String postgres) {
        postgres = switch (postgres) { case "serial" -> "int4"; case "bigserial" -> "int8"; case "smallserial" -> "int2"; default -> postgres; };
        if (postgres.startsWith("_")) {
            PgType element = of(source, postgres.substring(1));
            if (element.element != null || !ARRAY_ELEMENTS.contains(element.postgres)) throw SqlSource.error(source, "Unsupported array type: " + postgres);
            return new PgType(postgres, "List<" + element.kotlin + "?>", java.sql.Types.ARRAY, element);
        }
        return switch (postgres) {
            case "bool" -> scalar(postgres, "Boolean", java.sql.Types.BOOLEAN);
            case "int2" -> scalar(postgres, "Short", java.sql.Types.SMALLINT);
            case "int4" -> scalar(postgres, "Int", java.sql.Types.INTEGER);
            case "int8" -> scalar(postgres, "Long", java.sql.Types.BIGINT);
            case "float4" -> scalar(postgres, "Float", java.sql.Types.REAL);
            case "float8" -> scalar(postgres, "Double", java.sql.Types.DOUBLE);
            case "numeric" -> scalar(postgres, "java.math.BigDecimal", java.sql.Types.NUMERIC);
            case "text", "varchar", "bpchar", "name" -> scalar(postgres, "String", java.sql.Types.VARCHAR);
            // citext, xml and inet have no dedicated JDBC type; PostgreSQL casts them from text on input.
            case "citext", "xml", "inet" -> scalar(postgres, "String", java.sql.Types.OTHER);
            case "uuid" -> scalar(postgres, "java.util.UUID", java.sql.Types.OTHER);
            case "json", "jsonb" -> scalar(postgres, "String", java.sql.Types.OTHER);
            case "date" -> scalar(postgres, "java.time.LocalDate", java.sql.Types.DATE);
            case "time" -> scalar(postgres, "java.time.LocalTime", java.sql.Types.TIME);
            case "timestamp" -> scalar(postgres, "java.time.LocalDateTime", java.sql.Types.TIMESTAMP);
            case "timestamptz" -> scalar(postgres, "java.time.OffsetDateTime", java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            case "bytea" -> scalar(postgres, "ByteArray", java.sql.Types.BINARY);
            // A void result carries no value; only a :void query may select one.
            case "void" -> scalar(postgres, "Unit", java.sql.Types.OTHER);
            default -> throw SqlSource.error(source, "Unsupported PostgreSQL type '" + postgres + "'; cast explicitly to a supported type");
        };
    }

    boolean valueless() { return element == null && postgres.equals("void"); }

    /** A Kotlin enum is carried as its constant name, so only textual PostgreSQL types can hold one. */
    boolean textual() { return element == null && TEXTUAL.contains(postgres); }

    private static PgType scalar(String pg, String kt, int jdbc) { return new PgType(pg, kt, jdbc, null); }
}
