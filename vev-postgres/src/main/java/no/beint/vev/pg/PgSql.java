package no.beint.vev.pg;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class PgSql {
    private final String find;
    private final String findMultiple;
    private final String scanById;
    private final String insert;
    private final String update;
    private final String upsert;
    private final String delete;

    private PgSql(
            String find,
            String findMultiple,
            String scanById,
            String insert,
            String update,
            String upsert,
            String delete) {
        this.find = find;
        this.findMultiple = findMultiple;
        this.scanById = scanById;
        this.insert = insert;
        this.update = update;
        this.upsert = upsert;
        this.delete = delete;
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
                + " FROM unnest(?::" + id.codec().sqlType()
                + "[]) WITH ORDINALITY AS " + requested + "(\"key\", \"ordinality\")"
                + " LEFT JOIN " + table + " AS " + row
                + " ON " + row + "." + quoted(id.name()) + " = " + requested + ".\"key\""
                + " AND " + row + "." + quoted(tenant.name()) + " = ?"
                + " ORDER BY " + requested + ".\"ordinality\"";

        String scanById = "SELECT " + selectedColumns + " FROM " + table
                + " WHERE " + quoted(tenant.name()) + " = ?"
                + " ORDER BY " + quoted(id.name()) + " LIMIT ?";

        String insert = "INSERT INTO " + table + " (" + quotedColumns(columns) + ") VALUES ("
                + placeholders(columns.size()) + ") RETURNING " + selectedColumns;

        if (!(plan instanceof PgVersionPlan<?, ?, ?, ?, ?>)) {
            return new PgSql(find, findMultiple, scanById, insert, null, null, null);
        }

        PgColumn version = column(columns, PgColumn.Role.VERSION);
        List<PgColumn> mutableColumns = columns.stream()
                .filter(column -> column.role() == PgColumn.Role.VALUE)
                .toList();
        return new PgSql(
                find,
                findMultiple,
                scanById,
                insert,
                update(table, columns, mutableColumns, id, tenant, version),
                upsert(table, columns, mutableColumns, id, tenant, version),
                delete(table, id, tenant, version));
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

    String insert() {
        return insert;
    }

    String update() {
        return update;
    }

    String upsert() {
        return upsert;
    }

    String delete() {
        return delete;
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

    private static String upsert(
            String table,
            List<PgColumn> columns,
            List<PgColumn> mutableColumns,
            PgColumn id,
            PgColumn tenant,
            PgColumn version) {
        String input = "\"__vev_input\"";
        String updated = "\"__vev_updated\"";
        String inserted = "\"__vev_inserted\"";
        String target = "\"__vev_target\"";
        List<String> assignments = new ArrayList<>();
        for (PgColumn column : mutableColumns) {
            assignments.add(quoted(column.name()) + " = " + input + "." + quoted(column.name()));
        }
        assignments.add(quoted(version.name()) + " = " + target + "." + quoted(version.name()) + " + 1");
        return "WITH " + input + " (" + quotedColumns(columns) + ") AS (VALUES ("
                + typedPlaceholders(columns) + ")), "
                + updated + " AS (UPDATE " + table + " AS " + target
                + " SET " + String.join(", ", assignments)
                + " FROM " + input
                + " WHERE " + target + "." + quoted(id.name()) + " = " + input + "." + quoted(id.name())
                + " AND " + target + "." + quoted(tenant.name()) + " = " + input + "." + quoted(tenant.name())
                + " AND " + target + "." + quoted(version.name()) + " = " + input + "." + quoted(version.name())
                + " RETURNING " + columns(columns, target + ".") + "), "
                + inserted + " AS (INSERT INTO " + table + " (" + quotedColumns(columns) + ")"
                + " SELECT " + columns(columns, input + ".") + " FROM " + input
                + " WHERE " + input + "." + quoted(version.name()) + " = 0"
                + " AND NOT EXISTS (SELECT 1 FROM " + updated + ")"
                + " ON CONFLICT (" + quoted(id.name()) + ", " + quoted(tenant.name()) + ") DO NOTHING"
                + " RETURNING " + columns(columns, "") + ")"
                + " SELECT 0, " + columns(columns, "") + " FROM " + inserted
                + " UNION ALL SELECT 1, " + columns(columns, "") + " FROM " + updated
                + " UNION ALL SELECT 2, " + nullColumns(columns)
                + " FROM " + input
                + " WHERE " + input + "." + quoted(version.name()) + " = 0"
                + " AND NOT EXISTS (SELECT 1 FROM " + updated + ")"
                + " AND NOT EXISTS (SELECT 1 FROM " + inserted + ")"
                + " UNION ALL SELECT 2, " + nullColumns(columns)
                + " FROM " + table + " AS " + target + ", " + input
                + " WHERE " + input + "." + quoted(version.name()) + " <> 0"
                + " AND " + target + "." + quoted(id.name()) + " = " + input + "." + quoted(id.name())
                + " AND " + target + "." + quoted(tenant.name()) + " = " + input + "." + quoted(tenant.name())
                + " AND NOT EXISTS (SELECT 1 FROM " + updated + ")"
                + " AND NOT EXISTS (SELECT 1 FROM " + inserted + ")";
    }

    private static String delete(
            String table,
            PgColumn id,
            PgColumn tenant,
            PgColumn version) {
        String applied = "\"__vev_applied\"";
        String target = "\"__vev_target\"";
        String delete = "DELETE FROM " + table
                + " WHERE " + quoted(id.name()) + " = ?"
                + " AND " + quoted(tenant.name()) + " = ?"
                + " AND " + quoted(version.name()) + " = ?"
                + " RETURNING " + quoted(version.name());
        return "WITH " + applied + " AS (" + delete + ")"
                + " SELECT 0, " + quoted(version.name()) + " FROM " + applied
                + " UNION ALL SELECT 1, NULL::" + version.codec().sqlType()
                + " FROM " + table + " AS " + target
                + " WHERE " + target + "." + quoted(id.name()) + " = ?"
                + " AND " + target + "." + quoted(tenant.name()) + " = ?"
                + " AND NOT EXISTS (SELECT 1 FROM " + applied + ")";
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

    private static String typedPlaceholders(List<PgColumn> columns) {
        return columns.stream()
                .map(column -> "?::" + column.codec().sqlType())
                .collect(Collectors.joining(", "));
    }

    private static String quoted(String identifier) {
        return '"' + identifier + '"';
    }
}
