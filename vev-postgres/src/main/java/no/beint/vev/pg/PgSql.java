package no.beint.vev.pg;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class PgSql {
    private final String find;
    private final String findMultiple;
    private final String scanById;
    private final String scanByIdAfter;
    private final String insert;
    private final String insertMultiple;
    private final String update;
    private final String updateMultiple;
    private final Map<PgIndex<?, ?, ?, ?>, PgIndexSql> indexes;

    private PgSql(
            String find,
            String findMultiple,
            String scanById,
            String scanByIdAfter,
            String insert,
            String insertMultiple,
            String update,
            String updateMultiple,
            Map<PgIndex<?, ?, ?, ?>, PgIndexSql> indexes) {
        this.find = find;
        this.findMultiple = findMultiple;
        this.scanById = scanById;
        this.scanByIdAfter = scanByIdAfter;
        this.insert = insert;
        this.insertMultiple = insertMultiple;
        this.update = update;
        this.updateMultiple = updateMultiple;
        this.indexes = indexes;
    }

    static PgSql compile(PgPlan<?, ?, ?, ?> plan) {
        List<PgColumn> columns = plan.columns();
        PgColumn id = column(columns, PgColumn.Role.ID);
        PgColumn tenant = column(columns, PgColumn.Role.TENANT);
        String table = quoted(plan.schemaName()) + '.' + quoted(plan.tableName());
        String selectedColumns = columns(columns, "");
        String find = "SELECT " + selectedColumns + " FROM " + table
                + " WHERE " + quoted(id.name()) + " = ? AND " + quoted(tenant.name()) + " = ?";

        String row = "\"__vev_row\"";
        String requested = "\"__vev_requested\"";
        String findMultiple = "SELECT (" + row + "." + quoted(id.name()) + " IS NOT NULL), "
                + columns(columns, row + ".")
                + " FROM pg_catalog.unnest(?::" + id.codec().sqlType()
                + "[]) WITH ORDINALITY AS " + requested + "(\"key\", \"ordinality\")"
                + " LEFT JOIN " + table + " AS " + row
                + " ON " + row + "." + quoted(id.name()) + " = " + requested + ".\"key\""
                + " AND " + row + "." + quoted(tenant.name()) + " = ?"
                + " ORDER BY " + requested + ".\"ordinality\"";

        String scanById = "SELECT " + selectedColumns + " FROM " + table
                + " WHERE " + quoted(tenant.name()) + " = ?"
                + " ORDER BY " + quoted(id.name()) + " LIMIT ?";
        String scanByIdAfter = "SELECT " + selectedColumns + " FROM " + table
                + " WHERE " + quoted(tenant.name()) + " = ?"
                + " AND " + quoted(id.name()) + " > ?"
                + " ORDER BY " + quoted(id.name()) + " LIMIT ?";

        String insert = "INSERT INTO " + table + " (" + quotedColumns(columns) + ") VALUES ("
                + placeholders(columns.size()) + ") RETURNING " + selectedColumns;
        String insertMultiple = insertMultiple(table, columns, id, tenant);
        Map<PgIndex<?, ?, ?, ?>, PgIndexSql> indexes = compileIndexes(plan, table, selectedColumns, id, tenant);

        if (!(plan instanceof PgVersionPlan<?, ?, ?, ?, ?>)) {
            return new PgSql(
                    find, findMultiple, scanById, scanByIdAfter, insert, insertMultiple, null, null, indexes);
        }

        PgColumn version = column(columns, PgColumn.Role.VERSION);
        List<PgColumn> mutableColumns = columns.stream()
                .filter(column -> column.role() == PgColumn.Role.VALUE)
                .toList();
        return new PgSql(
                find,
                findMultiple,
                scanById,
                scanByIdAfter,
                insert,
                insertMultiple,
                update(table, columns, mutableColumns, id, tenant, version),
                updateMultiple(table, columns, mutableColumns, id, tenant, version),
                indexes);
    }

    String find() {
        return find;
    }

    String findMultiple() {
        return findMultiple;
    }

    String scanById() {
        return scanById;
    }

    String scanByIdAfter() {
        return scanByIdAfter;
    }

    String insert() {
        return insert;
    }

    String insertMultiple() {
        return insertMultiple;
    }

    String update() {
        return update;
    }

    String updateMultiple() {
        return updateMultiple;
    }

    PgIndexSql index(PgIndex<?, ?, ?, ?> index) {
        PgIndexSql statements = indexes.get(index);
        if (statements == null) {
            throw new IllegalArgumentException("Index token is not from this compiled PostgreSQL plan");
        }
        return statements;
    }

    private static Map<PgIndex<?, ?, ?, ?>, PgIndexSql> compileIndexes(
            PgPlan<?, ?, ?, ?> plan,
            String table,
            String selectedColumns,
            PgColumn id,
            PgColumn tenant) {
        Map<PgIndex<?, ?, ?, ?>, PgIndexSql> compiled = new IdentityHashMap<>();
        for (PgIndex<?, ?, ?, ?> index : plan.indexes()) {
            PgColumn value = plan.columns().get(index.columnIndex());
            String equality = quoted(tenant.name()) + " = ? AND " + quoted(value.name()) + " = ?";
            String nullEquality = quoted(tenant.name()) + " = ? AND " + quoted(value.name()) + " IS NULL";
            String orderAndLimit = " ORDER BY " + quoted(id.name()) + " LIMIT ?";
            String select = "SELECT " + selectedColumns + " FROM " + table + " WHERE ";
            String after = " AND " + quoted(id.name()) + " > ?";
            PgIndexSql statements = new PgIndexSql(
                    select + equality + orderAndLimit,
                    select + equality + after + orderAndLimit,
                    index instanceof PgNullableIndex<?, ?, ?, ?> ? select + nullEquality + orderAndLimit : null,
                    index instanceof PgNullableIndex<?, ?, ?, ?>
                            ? select + nullEquality + after + orderAndLimit
                            : null);
            compiled.put(index, statements);
        }
        return compiled;
    }

    private static String insertMultiple(
            String table,
            List<PgColumn> columns,
            PgColumn id,
            PgColumn tenant) {
        String arrays = "\"__vev_arrays\"";
        String input = "\"__vev_input\"";
        String row = "\"__vev_row\"";
        String inserted = "\"__vev_inserted\"";
        String expected = "\"__vev_expected\"";
        String arrayProjection = columns.stream()
                .map(column -> "?::" + column.codec().sqlType() + "[] AS " + quoted(column.name()))
                .collect(Collectors.joining(", "));
        String expandedArrays = columns.stream()
                .map(column -> "pg_catalog.unnest(" + arrays + "." + quoted(column.name()) + ")")
                .collect(Collectors.joining(", "));
        String cardinalityGuards = columns.stream()
                .map(column -> "pg_catalog.cardinality(" + arrays + "." + quoted(column.name()) + ") = "
                        + arrays + "." + expected)
                .collect(Collectors.joining(" AND "));
        String inputColumns = quotedColumns(columns) + ", \"__vev_ordinality\"";
        return "WITH " + arrays + " AS MATERIALIZED (SELECT " + arrayProjection
                + ", ?::\"pg_catalog\".\"int4\" AS " + expected + "), "
                + input + " AS MATERIALIZED (SELECT " + row + ".* FROM " + arrays
                + " CROSS JOIN LATERAL ROWS FROM (" + expandedArrays + ") WITH ORDINALITY AS "
                + row + "(" + inputColumns + ") WHERE " + cardinalityGuards + "), "
                + inserted + " AS (INSERT INTO " + table + " (" + quotedColumns(columns) + ") SELECT "
                + columns(columns, input + ".") + " FROM " + input
                + " ORDER BY " + input + ".\"__vev_ordinality\" RETURNING " + columns(columns, "") + ")"
                + " SELECT " + columns(columns, inserted + ".") + " FROM " + input
                + " JOIN " + inserted
                + " ON " + inserted + "." + quoted(id.name()) + " = " + input + "." + quoted(id.name())
                + " AND " + inserted + "." + quoted(tenant.name()) + " = " + input + "." + quoted(tenant.name())
                + " ORDER BY " + input + ".\"__vev_ordinality\"";
    }

    private static String update(
            String table,
            List<PgColumn> columns,
            List<PgColumn> mutableColumns,
            PgColumn id,
            PgColumn tenant,
            PgColumn version) {
        List<String> assignments = new ArrayList<>();
        for (PgColumn column : mutableColumns) {
            assignments.add(quoted(column.name()) + " = ?");
        }
        assignments.add(quoted(version.name()) + " = " + quoted(version.name()) + " + 1");
        String applied = "\"__vev_applied\"";
        String target = "\"__vev_target\"";
        String update = "UPDATE " + table + " SET " + String.join(", ", assignments)
                + " WHERE " + quoted(id.name()) + " = ?"
                + " AND " + quoted(tenant.name()) + " = ?"
                + " AND " + quoted(version.name()) + " = ?"
                + " RETURNING " + columns(columns, "");
        return "WITH " + applied + " AS (" + update + ")"
                + " SELECT 0, " + columns(columns, "") + " FROM " + applied
                + " UNION ALL SELECT 1, " + nullColumns(columns)
                + " FROM " + table + " AS " + target
                + " WHERE " + target + "." + quoted(id.name()) + " = ?"
                + " AND " + target + "." + quoted(tenant.name()) + " = ?"
                + " AND NOT EXISTS (SELECT 1 FROM " + applied + ")";
    }

    private static String updateMultiple(
            String table,
            List<PgColumn> columns,
            List<PgColumn> mutableColumns,
            PgColumn id,
            PgColumn tenant,
            PgColumn version) {
        String arrays = "\"__vev_arrays\"";
        String input = "\"__vev_input\"";
        String row = "\"__vev_row\"";
        String target = "\"__vev_target\"";
        String matched = "\"__vev_matched\"";
        String applied = "\"__vev_applied\"";
        String expected = "\"__vev_expected\"";
        String ordinality = "\"__vev_ordinality\"";
        String arrayProjection = columns.stream()
                .map(column -> "?::" + column.codec().sqlType() + "[] AS " + quoted(column.name()))
                .collect(Collectors.joining(", "));
        String expandedArrays = columns.stream()
                .map(column -> "pg_catalog.unnest(" + arrays + "." + quoted(column.name()) + ")")
                .collect(Collectors.joining(", "));
        String cardinalityGuards = columns.stream()
                .map(column -> "pg_catalog.cardinality(" + arrays + "." + quoted(column.name()) + ") = "
                        + arrays + "." + expected)
                .collect(Collectors.joining(" AND "));
        String inputColumns = quotedColumns(columns) + ", " + ordinality;
        String identityMatch = target + "." + quoted(id.name()) + " = " + input + "." + quoted(id.name())
                + " AND " + target + "." + quoted(tenant.name()) + " = " + input + "." + quoted(tenant.name());
        String versionMatch = target + "." + quoted(version.name()) + " = "
                + input + "." + quoted(version.name());
        List<String> assignments = new ArrayList<>();
        for (PgColumn column : mutableColumns) {
            assignments.add(quoted(column.name()) + " = " + input + "." + quoted(column.name()));
        }
        assignments.add(quoted(version.name()) + " = " + target + "." + quoted(version.name()) + " + 1");
        return "WITH " + arrays + " AS MATERIALIZED (SELECT " + arrayProjection
                + ", ?::\"pg_catalog\".\"int4\" AS " + expected + "), "
                + input + " AS MATERIALIZED (SELECT " + row + ".* FROM " + arrays
                + " CROSS JOIN LATERAL ROWS FROM (" + expandedArrays + ") WITH ORDINALITY AS "
                + row + "(" + inputColumns + ") WHERE " + cardinalityGuards + "), "
                + matched + " AS MATERIALIZED (SELECT " + input + "." + ordinality
                + " FROM " + input + " JOIN " + table + " AS " + target
                + " ON " + identityMatch + " AND " + versionMatch + "), "
                + applied + " AS (UPDATE " + table + " AS " + target
                + " SET " + String.join(", ", assignments) + " FROM " + input
                + " WHERE " + identityMatch + " AND " + versionMatch
                + " AND (SELECT pg_catalog.count(*) FROM " + matched + ") = "
                + "(SELECT " + expected + " FROM " + arrays + ")"
                + " RETURNING " + input + "." + ordinality + ", " + columns(columns, target + ".") + ")"
                + " SELECT " + ordinality + ", " + columns(columns, applied + ".") + " FROM " + applied
                + " ORDER BY " + ordinality;
    }

    private static PgColumn column(List<PgColumn> columns, PgColumn.Role role) {
        return columns.stream().filter(column -> column.role() == role).findFirst().orElseThrow();
    }

    private static String columns(List<PgColumn> columns, String qualifier) {
        return columns.stream()
                .map(column -> qualifier + quoted(column.name()))
                .collect(Collectors.joining(", "));
    }

    private static String quotedColumns(List<PgColumn> columns) {
        return columns.stream().map(column -> quoted(column.name())).collect(Collectors.joining(", "));
    }

    private static String nullColumns(List<PgColumn> columns) {
        return columns.stream()
                .map(column -> "NULL::" + column.codec().sqlType())
                .collect(Collectors.joining(", "));
    }

    private static String placeholders(int count) {
        return java.util.Collections.nCopies(count, "?").stream().collect(Collectors.joining(", "));
    }

    private static String quoted(String identifier) {
        return '"' + identifier + '"';
    }
}
