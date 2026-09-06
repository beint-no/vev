package no.beint.vev.pg;

import java.util.List;
import java.util.stream.Collectors;

final class PgCreationSql {
    private PgCreationSql() {
    }

    static String compile(PgPlan<?, ?, ?, ?> plan) {
        List<PgColumn> columns = plan.columns();
        List<PgColumn> values = columns.stream().filter(column -> column.role() == PgColumn.Role.VALUE).toList();
        PgColumn id = columns.stream().filter(column -> column.role() == PgColumn.Role.ID).findFirst().orElseThrow();
        PgColumn tenant = columns.stream().filter(column -> column.role() == PgColumn.Role.TENANT).findFirst().orElseThrow();
        String arrays = values.stream().map(column -> ", ?::" + column.codec().sqlType() + "[] AS " + q(column.name()))
                .collect(Collectors.joining());
        String guards = values.stream().map(column -> "pg_catalog.cardinality(a." + q(column.name()) + ") = a.\"__vev_size\"")
                .collect(Collectors.joining(" AND "));
        String input = columns.stream().map(column -> switch (column.role()) {
            case ID -> "pg_catalog.nextval(a.\"__vev_sequence\")::" + column.codec().sqlType() + " AS " + q(column.name());
            case TENANT -> "a.\"__vev_tenant\" AS " + q(column.name());
            case VERSION -> "0::" + column.codec().sqlType() + " AS " + q(column.name());
            case VALUE -> "r." + q(column.name());
        }).collect(Collectors.joining(", "));
        String expansion = values.isEmpty()
                ? "pg_catalog.generate_series(1, a.\"__vev_size\") r(\"__vev_ordinality\")"
                : "ROWS FROM (" + values.stream().map(column -> "pg_catalog.unnest(a." + q(column.name()) + ")")
                        .collect(Collectors.joining(", ")) + ") WITH ORDINALITY AS r("
                        + values.stream().map(column -> q(column.name())).collect(Collectors.joining(", "))
                        + ", \"__vev_ordinality\")";
        String names = columns.stream().map(column -> q(column.name())).collect(Collectors.joining(", "));
        String returned = columns.stream().map(column -> "created." + q(column.name())).collect(Collectors.joining(", "));
        return "WITH \"__vev_arrays\" AS MATERIALIZED (SELECT ?::pg_catalog.regclass AS \"__vev_sequence\", ?::"
                + tenant.codec().sqlType() + " AS \"__vev_tenant\", ?::pg_catalog.int4 AS \"__vev_size\"" + arrays + "), "
                + "\"__vev_input\" AS MATERIALIZED (SELECT " + input + ", r.\"__vev_ordinality\""
                + " FROM \"__vev_arrays\" a CROSS JOIN LATERAL " + expansion
                + (guards.isEmpty() ? "" : " WHERE " + guards) + "), "
                + "\"__vev_created\" AS (INSERT INTO " + q(plan.schemaName()) + '.' + q(plan.tableName())
                + " (" + names + ") OVERRIDING SYSTEM VALUE SELECT " + names
                + " FROM \"__vev_input\" ORDER BY \"__vev_ordinality\" RETURNING " + names + ")"
                + " SELECT input.\"__vev_ordinality\", input." + q(id.name()) + ", " + returned
                + " FROM \"__vev_input\" input JOIN \"__vev_created\" created"
                + " ON created." + q(id.name()) + " = input." + q(id.name())
                + " AND created." + q(tenant.name()) + " = input." + q(tenant.name())
                + " ORDER BY input.\"__vev_ordinality\"";
    }

    private static String q(String identifier) {
        return '"' + identifier + '"';
    }
}
