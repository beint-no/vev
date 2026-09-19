package no.beint.vev.compiler;

import java.nio.file.Path;

record PgType(String postgres, String kotlin, int jdbc, PgType element) {
    static PgType of(Path source, String postgres) {
        postgres = switch (postgres) { case "serial" -> "int4"; case "bigserial" -> "int8"; case "smallserial" -> "int2"; default -> postgres; };
        if (postgres.startsWith("_")) {
            PgType element = of(source, postgres.substring(1));
            if (element.element != null || !java.util.Set.of("bool", "int2", "int4", "int8", "float4", "float8", "numeric", "text", "varchar", "bpchar", "uuid").contains(element.postgres)) throw SqlSource.error(source, "Unsupported array type: " + postgres);
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
            case "uuid" -> scalar(postgres, "java.util.UUID", java.sql.Types.OTHER);
            case "json", "jsonb" -> scalar(postgres, "String", java.sql.Types.OTHER);
            case "date" -> scalar(postgres, "java.time.LocalDate", java.sql.Types.DATE);
            case "time" -> scalar(postgres, "java.time.LocalTime", java.sql.Types.TIME);
            case "timestamp" -> scalar(postgres, "java.time.LocalDateTime", java.sql.Types.TIMESTAMP);
            case "timestamptz" -> scalar(postgres, "java.time.OffsetDateTime", java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            case "bytea" -> scalar(postgres, "ByteArray", java.sql.Types.BINARY);
            default -> throw SqlSource.error(source, "Unsupported PostgreSQL type '" + postgres + "'; cast explicitly to a supported type");
        };
    }
    private static PgType scalar(String pg, String kt, int jdbc) { return new PgType(pg, kt, jdbc, null); }
}
